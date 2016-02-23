package software.coolstuff.installapex.command;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;

import org.apache.commons.io.output.NullOutputStream;
import org.apache.commons.lang.StringUtils;
import org.apache.velocity.app.VelocityEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.ui.velocity.VelocityEngineUtils;

import software.coolstuff.installapex.cli.CommandLineOption;
import software.coolstuff.installapex.exception.InstallApexException;
import software.coolstuff.installapex.exception.InstallApexException.Reason;
import software.coolstuff.installapex.service.apex.ApexParameter;
import software.coolstuff.installapex.service.apex.parser.ApexApplication;
import software.coolstuff.installapex.service.apex.parser.ApexApplicationParserService;
import software.coolstuff.installapex.service.database.DatabaseCheckService;
import software.coolstuff.installapex.service.upgrade.UpgradeParameter;
import software.coolstuff.installapex.service.upgrade.UpgradeService;

@Service
public class InstallCommand extends AbstractDataSourceCommand {

  private static final Logger log = LoggerFactory.getLogger(InstallCommand.class);

  private static final String KEY_SHOW_APEX_VERSION = "installCommand.showApexVersion";
  private static final String KEY_INSTALL_APEX_APPLICAITON = "installCommand.installApexApplication";
  private static final String KEY_UPGRADE_DATABASE = "installCommand.upgradeDatabase";

  private static final String KEY_EXTRACT_APEX_APPLICAITON = "installCommand.extractApexApplication";

  @Autowired
  private UpgradeService upgradeService;

  @Autowired
  private ApexApplicationParserService parserService;

  @Autowired
  private DatabaseCheckService databaseCheckService;

  @Autowired
  private VelocityEngine velocityEngine;

  @Value("${installCommand.sqlplus.scriptName}")
  private String sqlplusScriptName;

  @Value("${installCommand.sqlplus.scriptEncoding}")
  private String sqlplusScriptEncoding;

  @Override
  protected void executeWithInitializedDataSource() {
    String apexVersion = databaseCheckService.getApexVersion();
    printlnMessage(KEY_SHOW_APEX_VERSION, apexVersion);

    long workspace = getInstallationWorkspace();

    ApexApplication apexApplication = getInstallationCandidate();
    try {
      upgradeDatabase(apexApplication);
      installApexApplication(apexApplication, workspace);
    } catch (IOException | InterruptedException e) {
      throw new InstallApexException(Reason.ERROR_WHILE_INSTALL_WITH_SQLPLUS, e, apexApplication.getId(),
          apexApplication.getName());
    }
  }

  private long getInstallationWorkspace() {
    ApexParameter apexParameter = getSettings().getApexParameter();
    Map<String, Long> workspaces = databaseCheckService.getApexWorkspacesFor(apexParameter.getSchema());
    if (workspaces.isEmpty()) {
      throw new InstallApexException(Reason.NO_WORKSPACE_ASSIGNED, apexParameter.getSchema());
    }
    String workspace = apexParameter.getWorkspace();
    if (StringUtils.isBlank(workspace)) {
      if (workspaces.size() == 1) {
        // Installation Schema has only one Workspace assigned and no Workspace
        // has been defined on Command Line --> return this assigned Worksapce
        Collection<Long> workspaceIds = workspaces.values();
        return workspaceIds.toArray(new Long[] {})[0];
      }
      throw new InstallApexException(Reason.CLI_MISSING_REQUIRED_OPTION,
          CommandLineOption.APEX_TARGET_WORKSPACE.getLongOption("--"), getCommandType().getLongOption("--"));
    }
    if (!workspaces.containsKey(workspace.toUpperCase())) {
      throw new InstallApexException(Reason.REQUESTED_WORKSPACE_NOT_ASSIGNED, workspace, apexParameter.getSchema());
    }
    return workspaces.get(workspace.toUpperCase());
  }

  private void upgradeDatabase(ApexApplication apexApplication) {
    printlnMessage(KEY_UPGRADE_DATABASE, getSettings().getInstallSchemaConnect(), apexApplication.getId());
    UpgradeParameter upgradeParameter = getSettings().getUpgradeParameter();
    upgradeParameter.setApexApplication(apexApplication.getId());
    upgradeService.updateDatabase(upgradeParameter);
  }

  private void installApexApplication(ApexApplication apexApplication, long workspace)
      throws IOException, InterruptedException {
    Path temporaryDirectory = getSettings().getTemporaryDirectory(true);
    printlnMessage(KEY_EXTRACT_APEX_APPLICAITON, apexApplication.getId(), apexApplication.getName(),
        temporaryDirectory.toAbsolutePath());
    Path installationScript = parserService.extract(apexApplication, temporaryDirectory);
    printlnMessage(KEY_INSTALL_APEX_APPLICAITON, apexApplication.getName(), apexApplication.getId(),
        apexApplication.getVersion());
    ProcessBuilder sqlPlusBuilder = getSettings().getSQLPlusCommand();
    setExecutionDirectory(installationScript.getParent(), sqlPlusBuilder);
    Process sqlplus = sqlPlusBuilder.start();
    //@formatter:off
    Map<String, Object> context = new InstallContextBuilder()
        .setLineSize(getTerminalWidth())
        .setCommandSettings(getSettings())
        .setSqlPlusConnect(getSQLPlusConnect())
        .setInstallationScript(installationScript)
        .setApexApplication(apexApplication)
        .setWorkspace(workspace)
        .build();
    //@formatter:on
    redirectStandardInputToScript(sqlplus.getOutputStream(), context);
    PrintStream out = getOutputStream();
    redirectStream(sqlplus.getInputStream(), out);
    redirectStream(sqlplus.getErrorStream(), out);
    sqlplus.waitFor();
    if (sqlplus.exitValue() != 0) {
      throw new InstallApexException(Reason.INVALID_ERROR_CODE_BY_SQLPLUS, sqlplus.exitValue());
    }
  }

  private void setExecutionDirectory(Path extractionLocation, ProcessBuilder sqlPlusBuilder) {
    if (Files.isDirectory(extractionLocation)) {
      sqlPlusBuilder.directory(extractionLocation.toFile());
    } else {
      sqlPlusBuilder.directory(extractionLocation.getParent().toFile());
    }
  }

  private PrintStream getOutputStream() {
    if (getSettings().isQuiet()) {
      return new PrintStream(new NullOutputStream());
    }
    return System.out;
  }

  private void redirectStandardInputToScript(OutputStream outputStream, Map<String, Object> context)
      throws IOException {
    try (Writer output = new PrintWriter(outputStream)) {
      VelocityEngineUtils.mergeTemplate(velocityEngine, sqlplusScriptName, sqlplusScriptEncoding, context, output);
    }
  }

  private void redirectStream(InputStream inputStream, PrintStream outputStream) throws IOException {
    try (BufferedReader input = new BufferedReader(new InputStreamReader(inputStream))) {
      for (String line = null; (line = input.readLine()) != null;) {
        outputStream.println(line);
      }
    }
  }

  @Override
  protected CommandType getCommandType() {
    return CommandType.INSTALL;
  }

}
