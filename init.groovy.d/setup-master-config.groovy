@Grapes([
  @Grab(group = 'org.yaml', module = 'snakeyaml', version = '1.17')
])

import org.yaml.snakeyaml.Yaml
import java.util.logging.Logger
import com.cloudbees.plugins.credentials.*
import com.cloudbees.plugins.credentials.CredentialsStore

// import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials
import com.cloudbees.plugins.credentials.common.*
import com.cloudbees.plugins.credentials.domains.*

// import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl
// import com.cloudbees.plugins.credentials.CredentialsScope
import com.cloudbees.plugins.credentials.impl.*;
import com.cloudbees.plugins.credentials.*;
import com.cloudbees.plugins.credentials.domains.*;

import com.cloudbees.jenkins.plugins.sshcredentials.impl.*

import jenkins.model.*
import jenkins.security.*
import hudson.model.*
import hudson.security.*
import hudson.plugins.sshslaves.*;

import hudson.security.SecurityRealm
import hudson.security.AuthorizationStrategy
import org.jenkinsci.plugins.GithubSecurityRealm
import org.jenkinsci.plugins.GithubAuthorizationStrategy

env = System.getenv()
JENKINS_SETUP_YAML = env['JENKINS_SETUP_YAML'] ?: "${env['JENKINS_CONFIG_HOME']}/setup.yml"
Logger logger = Logger.getLogger('setup-master-config.groovy')
def config = new Yaml().load(new File(JENKINS_SETUP_YAML).text)

// setup Time Zone
Thread.start {
  TZ = env['JENKINS_TZ'] ?: config.time_zone ?: 'America/New_York'
  System.setProperty('org.apache.commons.jelly.tags.fmt.timeZone', TZ)
}

// setup master executors
Thread.start {
  def JENKINS = Jenkins.getInstance()
  int executors = env['JENKINS_EXECUTORS'] ?: config.executors.master.toInteger() ?: 2
  int current_executors = JENKINS.getNumExecutors()
  if (current_executors != executors) {
    JENKINS.setNumExecutors(executors)
    JENKINS.save()
  }
}

// setup global credentials
Thread.start {
  def JENKINS = Jenkins.getInstance()
  def PLUGIN = 'com.cloudbees.plugins.credentials.SystemCredentialsProvider'
  credentials_store = JENKINS.getExtensionList(PLUGIN)[0].getStore()

  config.credentials.each {
    it.global.each {
      Credentials credentials = (Credentials) new UsernamePasswordCredentialsImpl(CredentialsScope.USER,
        it.id, it.description, it.username, it.password)

      credentials_store.addCredentials(Domain.global(), credentials)
    }
  }
  logger.info('Configured Global Credentials')
}

// setup global git config
Thread.start {
  if (Jenkins.instance.pluginManager.activePlugins.find { it.shortName == 'git' } != null) {
    def PLUGIN = 'hudson.plugins.git.GitSCM'
    def globalConfigName = config.git.config.name ?: 'sir-jenkins'
    def globalConfigEmail = config.git.config.email ?: 'admin@example.io'

    def descriptor = Jenkins.instance.getDescriptor(PLUGIN)
    if (globalConfigName != descriptor.getGlobalConfigName()) {
      descriptor.setGlobalConfigName(globalConfigName)
    }
    if (globalConfigEmail != descriptor.getGlobalConfigEmail()) {
      descriptor.setGlobalConfigEmail(globalConfigEmail)
    }
    if (!descriptor.equals(Jenkins.instance.getDescriptor(PLUGIN))) {
      descriptor.save()
    }
    logger.info('Configured Git SCM')
  }
}

// setup Jenkins generics
Thread.start {
  def JENKINS = Jenkins.getInstance()
  def PLUGIN = 'jenkins.model.JenkinsLocationConfiguration'
  def descriptor = JENKINS.getDescriptor(PLUGIN)
  def HOSTNAME = env['HOSTNAME'].toString()
  def JENKINS_LOC_URL = "${config.web_proto}://${HOSTNAME}:${config.web_port}"

  if (JENKINS_LOC_URL != descriptor.getUrl()) {
    descriptor.setUrl(JENKINS_LOC_URL)
  }
  if (config.admin.email != descriptor.getAdminAddress()) {
    descriptor.setAdminAddress(config.admin.email)
  }
  if (!descriptor.equals(Jenkins.instance.getDescriptor(PLUGIN))) {
    descriptor.save()
  }
  logger.info('Configured Admin Address')
}

// setup master ssh key
Thread.start {
  if (Jenkins.instance.pluginManager.activePlugins.find { it.shortName == 'ssh-credentials' } != null
    && new File('/var/jenkins_home/.ssh').exists()) {
    // adds SSHUserPrivateKey From the Jenkins master ${HOME}/.ssh
    def PLUGIN = 'com.cloudbees.plugins.credentials.SystemCredentialsProvider'
    credentials_store = Jenkins.instance.getExtensionList(PLUGIN)[0].getStore()

    // signature Scope, Id, Username, Keysource, Passphrase, Description
    // https://github.com/jenkinsci/ssh-credentials-plugin/blob/master/src/main/java/com/cloudbees/jenkins/plugins/sshcredentials/impl/BasicSSHUserPrivateKey.java
    credentials = new BasicSSHUserPrivateKey(
      CredentialsScope.GLOBAL, 'ssh-key-sirjenkins', 'sirjenkins',
      new BasicSSHUserPrivateKey.UsersPrivateKeySource(), '', 'description')

    credentials_store.addCredentials(Domain.global(), credentials)
    logger.info('Configured SSH Credentials')
  }
}

// setup matrix-auth configuration
Thread.start {
  def JENKINS = Jenkins.getInstance()
  if (JENKINS.pluginManager.activePlugins.find { it.shortName == 'matrix-auth' } != null) {
    def hudson_realm = new HudsonPrivateSecurityRealm(false)
    def admin_username = env['JENKINS_ADMIN_USERNAME'] ?: config.admin.username ?: 'admin'
    def admin_password = env['JENKINS_ADMIN_PASSWORD'] ?: config.admin.password ?: 'password'
    hudson_realm.createAccount(admin_username, admin_password)

    JENKINS.setSecurityRealm(hudson_realm)

    def strategy = new hudson.security.GlobalMatrixAuthorizationStrategy()
    //  Setting Anonymous Permissions
    strategy.add(hudson.model.Hudson.READ, 'anonymous')
    strategy.add(hudson.model.Item.BUILD, 'anonymous')
    strategy.add(hudson.model.Item.CANCEL, 'anonymous')
    strategy.add(hudson.model.Item.DISCOVER, 'anonymous')
    strategy.add(hudson.model.Item.READ, 'anonymous')
    // Setting Admin Permissions
    strategy.add(Jenkins.ADMINISTER, 'admin')
    // Setting easy settings for local development
    if (env['BUILD_ENV'] == 'local') {
      //  Overall Permissions
      strategy.add(hudson.model.Hudson.ADMINISTER, 'anonymous')
      strategy.add(hudson.PluginManager.CONFIGURE_UPDATECENTER, 'anonymous')
      strategy.add(hudson.model.Hudson.READ, 'anonymous')
      strategy.add(hudson.model.Hudson.RUN_SCRIPTS, 'anonymous')
      strategy.add(hudson.PluginManager.UPLOAD_PLUGINS, 'anonymous')
    }

    if (!hudson_realm.equals(Jenkins.instance.getSecurityRealm())) {
      // Jenkins.instance.setSecurityRealm(hudson_realm)
      // Jenkins.instance.save()
      JENKINS.setAuthorizationStrategy(strategy)
      JENKINS.save()
    }
    logger.info('Configured AuthorizationStrategy')
  }
}

// setup Mailer configuration
Thread.start {
  def JENKINS = Jenkins.getInstance()
  def PLUGIN = 'hudson.tasks.Mailer'
  def descriptor = JENKINS.getDescriptor(PLUGIN)
  def smtpEmail = env['SMTP_EMAIL'] ?: config.mailer.smtp_email ?: ''
  def smtpHost = env['SMTP_HOST'] ?: config.mailer.smtp_host ?: 'smtp.gmail.com'
  def smtpAuthPasswordSecret = env['SMTP_PASSWORD'] ?: config.mailer.smtp_password ?: ''

  descriptor.setSmtpAuth(smtpEmail, "${smtpAuthPasswordSecret}")
  descriptor.setReplyToAddress(smtpEmail)
  descriptor.setSmtpHost(smtpHost)
  descriptor.setUseSsl(true)
  descriptor.setSmtpPort('465')
  descriptor.setCharset('UTF-8')

  descriptor.save()

  logger.info('Configured Mailer')
}

// setup master-slave security
Thread.start {
  // import jenkins.security.*
  if (config.set_master_kill_switch != null) {
    // Configure global master-slave security
    def master_slave_security = { instance = 'Jenkins.instance', home = env['JENKINS_HOME'], disabled = config.set_master_kill_switch ->
      new File(home + 'secrets/filepath-filters.d').mkdirs()
      new File(home + 'secrets/filepath-filters.d/50-gui.conf').createNewFile()
      new File(home + 'secrets/whitelisted-callables.d').mkdirs()
      new File(home + 'secrets/whitelisted-callables.d/gui.conf').createNewFile()
      instance.getInjector().getInstance(jenkins.security.s2m.AdminWhitelistRule.class).setMasterKillSwitch(disabled)
    }
    logger.info('Enabled Master -> Slave Security')
  }
}


Thread.start {
  if (Jenkins.instance.pluginManager.activePlugins.find { it.shortName == 'github-oauth' } != null) {

    String githubWebUri = env['GITHUB_WEB_URI'] ?: config.github.oauth.web_uri ?: 'https://github.com'
    String githubApiUri = env['GITHUB_API_URI'] ?: config.github.oauth.api_uri ?: 'https://api.github.com'
    String clientID = env['GITHUB_CLIENT_ID'] ?: config.github.oauth.client_id ?: 'someid'
    String clientSecret = env['GITHUB_CLIENT_SECRET'] ?: config.github.oauth.client_secret ?: 'somesecret'
    String oauthScopes = 'read:org'

    SecurityRealm github_realm = new GithubSecurityRealm(githubWebUri, githubApiUri, clientID, clientSecret, oauthScopes)
    //check for equality, no need to modify the runtime if no settings changed
    if (!github_realm.equals(Jenkins.instance.getSecurityRealm())) {
      Jenkins.instance.setSecurityRealm(github_realm)
      Jenkins.instance.save()
    }

    //----

    //permissions are ordered similar to web UI
    //Admin User Names
    String adminUserNames = env['JENKINS_ADMIN_USERNAME'] ?: config.admin.username ?: 'admin'
    //Participant in Organization
    String organizationNames = env['GITHUB_ORG'] ?: config.github.orgname ?: ''
    //Use Github repository permissions
    boolean useRepositoryPermissions = true
    //Grant READ permissions to all Authenticated Users
    boolean authenticatedUserReadPermission = false
    //Grant CREATE Job permissions to all Authenticated Users
    boolean authenticatedUserCreateJobPermission = false
    //Grant READ permissions for /github-webhook
    boolean allowGithubWebHookPermission = false
    //Grant READ permissions for /cc.xml
    boolean allowCcTrayPermission = false
    //Grant READ permissions for Anonymous Users
    boolean allowAnonymousReadPermission = false
    //Grant ViewStatus permissions for Anonymous Users
    boolean allowAnonymousJobStatusPermission = false

    AuthorizationStrategy github_authorization = new GithubAuthorizationStrategy(adminUserNames,
      authenticatedUserReadPermission,
      useRepositoryPermissions,
      authenticatedUserCreateJobPermission,
      organizationNames,
      allowGithubWebHookPermission,
      allowCcTrayPermission,
      allowAnonymousReadPermission,
      allowAnonymousJobStatusPermission)

    //check for equality, no need to modify the runtime if no settings changed
//    if (!github_authorization.equals(Jenkins.instance.getAuthorizationStrategy())) {
//      Jenkins.instance.setAuthorizationStrategy(github_authorization)
    logger.info('Saving Github authorisation strategy')

    Jenkins.instance.save()
//    }
  } else {
    logger.info('Github oauth plugin not found')
  }
}
