package org.javastack.sftpserver.server;

import org.apache.mina.core.buffer.IoBuffer;
import org.apache.sshd.common.Factory;
import org.apache.sshd.common.NamedFactory;
import org.apache.sshd.common.PropertyResolver;
import org.apache.sshd.common.PropertyResolverUtils;
import org.apache.sshd.common.channel.Channel;
import org.apache.sshd.common.compression.BuiltinCompressions;
import org.apache.sshd.common.compression.Compression;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.file.FileSystemFactory;
import org.apache.sshd.common.file.root.RootedFileSystemProvider;
import org.apache.sshd.common.mac.BuiltinMacs;
import org.apache.sshd.common.mac.Mac;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.util.GenericUtils;
import org.apache.sshd.common.util.SecurityUtils;
import org.apache.sshd.server.*;
import org.apache.sshd.server.auth.password.PasswordAuthenticator;
import org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator;
import org.apache.sshd.server.channel.ChannelSessionFactory;
import org.apache.sshd.server.keyprovider.AbstractGeneratorHostKeyProvider;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.scp.ScpCommandFactory;
import org.apache.sshd.server.session.ServerSession;
import org.apache.sshd.server.subsystem.sftp.SftpEventListener;
import org.apache.sshd.server.subsystem.sftp.SftpSubsystem;
import org.apache.sshd.server.subsystem.sftp.SftpSubsystemFactory;

import org.javastack.sftpserver.entity.User;
import org.javastack.sftpserver.readonly.ReadOnlyRootedFileSystemProvider;
import org.javastack.sftpserver.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.xml.bind.DatatypeConverter;
import java.io.*;
import java.math.BigInteger;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.file.FileSystem;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.PublicKey;
import java.security.interfaces.DSAPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.util.*;

/**
 * SFTP Server
 * 
 * @author Guillermo Grandes / guillermo.grandes[at]gmail.com
 * ?????????????????????spring ??????????????????????????????????????????
 */
@Component
public class Server implements  PublickeyAuthenticator,PasswordAuthenticator{

    static UserService userService;

	/**
	 * ??????????????????
	 */
	public static final String CONFIG_FILE = "sftpd.properties";
	public static final String HTPASSWD_FILE = "/htpasswd";
	public static final String HOSTKEY_FILE_PEM = "keys/hostkey.pem";
	public static final String HOSTKEY_FILE_SER = "keys/hostkey.ser";

	//.???????????????????????????????????? ???????????????????????????????????????????????????????????????
	private static final Logger LOG = LoggerFactory.getLogger(Server.class);
	private Config db;
	private SshServer sshd;
	private volatile boolean running = true;
    public static String homepath;

    /**
	 *
	 * public static void main(final String[] args) {
	 new Server().start();
	 }
	 */

	protected void setupFactories() {
		final CustomSftpSubsystemFactory sftpSubsys = new CustomSftpSubsystemFactory();
		sshd.setSubsystemFactories(Arrays.<NamedFactory<Command>>asList(sftpSubsys));
		sshd.setMacFactories(Arrays.<NamedFactory<Mac>>asList( //
				BuiltinMacs.hmacsha512, //
				BuiltinMacs.hmacsha256, //
				BuiltinMacs.hmacsha1));
		sshd.setChannelFactories(Arrays.<NamedFactory<Channel>>asList(ChannelSessionFactory.INSTANCE));
	}

	protected void setupDummyShell(final boolean enable) {
		sshd.setShellFactory(enable ? new SecureShellFactory() : null);
	}

	protected void setupKeyPair() {
		final AbstractGeneratorHostKeyProvider provider;
		//?????????????????????
		if (SecurityUtils.isBouncyCastleRegistered()) {
			provider = SecurityUtils.createGeneratorHostKeyProvider(new File(HOSTKEY_FILE_PEM).toPath());
		} else {
			provider = new SimpleGeneratorHostKeyProvider(new File(HOSTKEY_FILE_SER));
		}
		provider.setAlgorithm(KeyUtils.RSA_ALGORITHM);
		sshd.setKeyPairProvider(provider);
	}

	protected void setupScp() {
		sshd.setCommandFactory(new ScpCommandFactory());
		sshd.setFileSystemFactory(new SecureFileSystemFactory(db));
		sshd.setTcpipForwardingFilter(null);
		sshd.setAgentFactory(null);
	}
   //????????????
	protected void setupAuth() {

		sshd.setPasswordAuthenticator(this);
		sshd.setPublickeyAuthenticator(this);
		sshd.setGSSAuthenticator(null);
	}

	protected void setupSysprops() {
		sshd.setParentPropertyResolver(PropertyResolver.EMPTY);
	}


	protected void loadHtPasswd() throws IOException {
		InputStream is = null;
		BufferedReader r = null;
		try {
			//??????flase
			final boolean htEnabled = Boolean.parseBoolean(db.getHtValue(Config.PROP_HT_ENABLED));
			/**
			 * ??????????????????
			 * ?????????sftpserver.user.test.enableflag??????
			 */
			if (!htEnabled) {
				return;
			}
			final String htHome = db.getHtValue(Config.PROP_HT_HOME);
			final boolean htEnableWrite = Boolean.parseBoolean(db.getHtValue(Config.PROP_HT_ENABLE_WRITE));
			is = getClass().getResourceAsStream(HTPASSWD_FILE);
			r = new BufferedReader(new InputStreamReader(is));
			if (is == null) {
				LOG.error("htpasswd file " + HTPASSWD_FILE + " not found in classpath");
				return;
			}
			String line = null;
			int c = 0;
			while ((line = r.readLine()) != null) {
				if (line.startsWith("#"))
					continue;
				final String[] tok = line.split(":", 2);
				if (tok.length != 2)
					continue;
				final String user = tok[0];
				final String auth = tok[1];
				db.setValue(user, Config.PROP_PWD, auth);
				db.setValue(user, Config.PROP_HOME, htHome);
				db.setValue(user, Config.PROP_ENABLED, htEnabled);
				db.setValue(user, Config.PROP_ENABLE_WRITE, htEnableWrite);
				c++;
			}
			LOG.info("htpasswd file loaded " + c + " lines");
		} finally {
			closeQuietly(r);
			closeQuietly(is);
		}
	}
	//??????????????????
	protected void setupCompress(final boolean enable) {
		// Compression is not enabled by default
		// You need download and compile:
		// http://www.jcraft.com/jzlib/
		if (enable) {
			sshd.setCompressionFactories(Arrays.<NamedFactory<Compression>>asList( //
					BuiltinCompressions.none, //
					BuiltinCompressions.zlib, //
					BuiltinCompressions.delayedZlib));
		} else {
			sshd.setCompressionFactories(Arrays.<NamedFactory<Compression>>asList( //
					BuiltinCompressions.none));
		}
	}

	/**
	 * ????????? ??????????????????
	 */
	protected  Config loadConfig()  {
		    Properties db = new Properties();
		String path = Thread.currentThread().getContextClassLoader().getResource(CONFIG_FILE).getPath();
		try {
			path= URLDecoder.decode(path,"UTF-8");
		} catch (UnsupportedEncodingException e) {
			e.printStackTrace();
		}
		//BufferedReader bufferedReader = null;
		FileInputStream is = null;
		try {
			//bufferedReader=new BufferedReader(new FileReader(CONFIG_FILE));
			//????????????????????????????????? ?????????????????????resources?????????
			is = new FileInputStream(path);
			//??????
			if (is == null) {
				LOG.error("Config file " + CONFIG_FILE + " not found in classpath");
			} else {
				db.load(is);
				homepath=db.getProperty("sftpserver.parentdirectory");
				LOG.info("Config file loaded " + db.size() + " lines");
			}
		} catch (IOException e) {
			LOG.error("IOException " + e.toString(), e);
		} finally {
			closeQuietly(is);
		}
		//???properties????????????
		return new Config(db);
	}

	private void closeQuietly(final Closeable c) {
		if (c != null) {
			try {
				c.close();
			} catch (Exception ign) {
			}
		}
	}

	private void hackVersion() {
		PropertyResolverUtils.updateProperty(sshd, ServerFactoryManager.SERVER_IDENTIFICATION, "SSHD");
	}

	private void setupMaxPacketLength() {
		final int maxPacketLength = db.getMaxPacketLength();
		if (maxPacketLength > 1) {
			PropertyResolverUtils.updateProperty(sshd, SftpSubsystem.MAX_PACKET_LENGTH_PROP, maxPacketLength);
		}
	}
	/**
	 * ????????????
	 */
	public  void start() {

		LOG.info("Starting");
		//????????????
		db = loadConfig();
		LOG.info("BouncyCastle enabled=" + SecurityUtils.isBouncyCastleRegistered());
        //??????sshd
		sshd = SshServer.setUpDefaultServer();

		LOG.info("SSHD " + sshd.getVersion());
		hackVersion();
		//?????????????????????
		setupMaxPacketLength();
		//????????????
		setupFactories();
		//??????key
		setupKeyPair();
		//????????????????????????
		setupScp();
      //????????????
		setupAuth();
		//
		setupSysprops();

		try {
			final int port = db.getPort();
			final boolean enableCompress = db.enableCompress();
			final boolean enableDummyShell = db.enableDummyShell();
			setupCompress(enableCompress);
			setupDummyShell(enableDummyShell);
          //????????????????????????
			loadHtPasswd();
			sshd.setPort(port);
			LOG.info("Listen on port=" + port);
			final Server thisServer = this;
			Runtime.getRuntime().addShutdownHook(new Thread() {
				public void run() {
					thisServer.stop();
				}
			});
			//??????sshd??????
			sshd.start();
		} catch (Exception e) {
			LOG.error("Exception " + e.toString(), e);
		}
		while (running) {
			try {
				Thread.sleep(1000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}

	public void stop() {
		LOG.info("Stopping");
		running = false;
		try {
			sshd.stop();
		} catch (IOException e) {
			try {
				sshd.stop(true);
			} catch (IOException ee) {
				LOG.error("Failed to stop", ee);
			}
		}
	}

	/**
	 * ????????????
	 */
	@Override
	public boolean authenticate(final String username, final String password, final ServerSession session) {
		LOG.info("Request auth (Password) for username=" + username);
		if ((username != null) && (password != null)) {
			return db.checkUserPassword(username, password);
		}
		return false;
	}

	/**
	 * ????????????
	 * @param username
	 * @param key
	 * @param session
	 * @return
	 */
	@Override
	public boolean authenticate(final String username, final PublicKey key, final ServerSession session) {
		LOG.info("Request auth (PublicKey) for username=" + username);
		// File f = new File("/home/" + username + "/.ssh/authorized_keys");
		if ((username != null) && (key != null)) {
			return db.checkUserPublicKey(username, key);
		}
		return false;
	}

	// =================== Helper Classes

	static class Config {
		// Global config
		public static final String BASE = "sftpserver";
		public static final String PROP_GLOBAL = BASE + "." + "global";
		public static final String PROP_PORT = "port";
		public static final String PROP_COMPRESS = "compress";
		public static final String PROP_DUMMY_SHELL = "dummyshell";
		public static final String PROP_MAX_PACKET_LENGTH = "maxPacketLength";
		// HtPasswd config http??????????????????
		public static final String PROP_HTPASSWD = BASE + "." + "htpasswd";
		public static final String PROP_HT_HOME = "homedirectory";
		public static final String PROP_HT_ENABLED = "enableflag";
		public static final String PROP_HT_ENABLE_WRITE = "writepermission"; // true / false
		// User config
		public static final String PROP_BASE_USERS = BASE + "." + "user";
		public static final String PROP_PWD = "userpassword";
		public static final String PROP_KEY = "userkey" + ".";
		public static final String PROP_HOME = "homedirectory";
		public static final String PROP_ENABLED = "enableflag"; // true / false
		public static final String PROP_ENABLE_WRITE = "writepermission"; // true / false
		public User datauser=null;
		/**
		 *
		 */
		public  Properties db;

		/**
		 * ?????????????????????db??????
		 * @param db
		 */
		public Config( Properties db) {
			this.db = db;
		}
        // Global config
		//????????????
		public boolean enableCompress() {
			return Boolean.parseBoolean(getValue(PROP_COMPRESS));
		}

       //????????????shell
		public boolean enableDummyShell() {
			return Boolean.parseBoolean(getValue(PROP_DUMMY_SHELL));
		}

		//???????????????
		public int getPort() {
			//System.out.println(getValue(PROP_PORT));
			return Integer.parseInt(getValue(PROP_PORT));
		}
        //?????????????????????
		public int getMaxPacketLength() {
			final String v = getValue(PROP_MAX_PACKET_LENGTH);
			//????????????????????????64kb
			if (v == null) {
				// FIXME: Workaround for BUG in SSHD-CORE
				// https://issues.apache.org/jira/browse/SSHD-725
				// https://issues.apache.org/jira/browse/SSHD-728
				return (64 * 1024); // 64KB
			}
			return Integer.parseInt(v);
		}
       //????????????
		private final String getValue(final String key) {
			if (key == null)
				return null;
			//?????????????????????????????????
			return db.getProperty(PROP_GLOBAL + "." + key);
		}

		private final String getHtValue(final String key) {
			if (key == null)
				return null;
			return db.getProperty(PROP_HTPASSWD + "." + key);
		}

		// User config

		/**
		 * sftpserver.user.test.userpassword
		 * @param user ??????????????????
		 * @param key ?????????????????? ???PROP_HOME???
		 * @return
		 */
		private final String getValue(final String user, final String key) {
			if ((user == null) || (key == null))
				return null;
			final String value = db.getProperty(PROP_BASE_USERS + "." + user + "." + key);
			//????????????????????????????????????????????????????????????
			return ((value == null) ? null : value.trim());
		}

		/**
		 * ???????????????htpasswd????????????????????????????????????
		 * @param user
		 * @param key
		 * @param value
		 */
		private final void setValue(final String user, final String key, final Object value) {
			System.out.println("flag");
			if ((user == null) || (key == null) || (value == null))
				return;
			db.setProperty(PROP_BASE_USERS + "." + user + "." + key, String.valueOf(value));
		}

		/**
		 *
		 * @param user
		 * @return
		 */
		public boolean isEnabledUser(final String user) {
			//??????????????????
			//??????true
			final String value = getValue(user, PROP_ENABLED);
			if (value == null)
				return false;
			return Boolean.parseBoolean(value);
		}


//????????????
		public boolean checkUserPassword(final String user, final String pwd) {
			final StringBuilder sb = new StringBuilder(96);
			boolean traceInfo = false;
			boolean authOk = false;
			sb.append("Request auth (Password) for username=").append(user).append(" ");
			/**
			 * ???????????????????????????????????????
			 */
		   datauser=userService.getUserByName(user);
			String flag=datauser.getEnableflag();
			/**
			 * ??????????????????????????? ???????????? ?????????
			 * ???????????????????????? ???????????????????????????""
			 */
          if(flag==null||flag.equals("")){
			  userService.insertColumn(user);
			  datauser=userService.getUserByName(user);
		  }
			setproperties(datauser);
			try {
				if (!isEnabledUser(user)) {
					sb.append("(user disabled)");
					return authOk;
				}
				//???????????????????????????????????????????????????????????????
			    String value=datauser.getPass();
				if (value == null) {
					sb.append("(no password)");
					return authOk;
				}
				//????????????
				final boolean isCrypted = PasswordEncrypt.isCrypted(value);
				//????????????????????????????????????????????? ?????????????????????????????????
				authOk = isCrypted ? PasswordEncrypt.checkPassword(value, pwd) : value.equals(pwd);
				//????????????????????????
				sb.append(isCrypted ? "(encrypted)" : "(unencrypted)");
				traceInfo = isCrypted;
			} finally {
				sb.append(": ").append(authOk ? "OK" : "FAIL");
				if (authOk) {
					if (traceInfo) {
						//???????????????info?????????
						LOG.info(sb.toString());
					} else {
						//?????????????????????????????????
						LOG.warn(sb.toString());
					}
				} else {
					//????????????
					LOG.error(sb.toString());
				}
			}
			return authOk;
		}

		/**
		 * ??????????????????
		 */
       private void setproperties(User user){
       	  db.setProperty("sftpserver.user."+user.getUsername()+".homedirectory",user.getHomedirectory());
       	  db.setProperty("sftpserver.user."+user.getUsername()+".writepermission",user.getWritepermission());
       	  db.setProperty("sftpserver.user."+user.getUsername()+".enableflag",user.getEnableflag());
	   }



		public boolean checkUserPublicKey(final String user, final PublicKey key) {
			final String encodedKey = PublicKeyHelper.getEncodedPublicKey(key);
			final StringBuilder sb = new StringBuilder(96);
			boolean authOk = false;
			sb.append("Request auth (PublicKey) for username=").append(user);
			sb.append(" (").append(key.getAlgorithm()).append(")");
			try {
				if (!isEnabledUser(user)) {
					sb.append(" (user disabled)");
					return authOk;
				}
				for (int i = 1; i < 1024; i++) {
					final String value = getValue(user, PROP_KEY + i);
					if (value == null) {
						if (i == 1)
							sb.append(" (no publickey)");
						break;
					} else if (value.equals(encodedKey)) {
						authOk = true;
						break;
					}
				}
			} finally {
				sb.append(": ").append(authOk ? "OK" : "FAIL");
				if (authOk) {
					LOG.info(sb.toString());
				} else {
					LOG.error(sb.toString());
				}
			}
			return authOk;
		}
       //????????????
		private void writeProperties(String user) {
			System.out.println("????????????");
			Properties properties = new Properties();
			OutputStream output = null;
			try {
				//???????????????????????????
				output = new FileOutputStream("D:\\github\\sftpserver-master\\src\\main\\resources\\sftpd.properties",true);
				properties.setProperty("sftpserver.user."+user+".homedirectory",homepath+"/"+user+"/");
				properties.setProperty("sftpserver.user."+user+".enableflag","true");
				properties.setProperty("sftpserver.user."+user+".writepermission","true");
				properties.store(output, "");// ???????????????????????????
				output.flush();
				output.close();
			} catch (IOException io) {
				System.out.println("??????");
				io.printStackTrace();
			} finally {
				if (output != null) {
					try {
						output.close();
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
			}
		}
		//????????????????????????
		private Properties reload(){
			 Properties redb = new Properties();
			String path = Thread.currentThread().getContextClassLoader().getResource(CONFIG_FILE).getPath();

			//BufferedReader bufferedReader = null;
			FileInputStream rs = null;
			try {
				//bufferedReader=new BufferedReader(new FileReader(CONFIG_FILE));
				//????????????????????????????????? ?????????????????????resources?????????
				rs = new FileInputStream(path);
				//??????
				if (rs == null) {
					LOG.error( CONFIG_FILE + " not found in classpath");
				} else {
					redb.load(rs);
					LOG.info("Config file reloaded " + redb.size() + " lines");
				}
			} catch (IOException e) {
				LOG.error("IOException " + e.toString(), e);
			} finally {
				try {
					rs.close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
			//???properties????????????
			return redb;
		}
     //???????????????
		public String getHome(final String user) {
			try {
				final File home = new File(getValue(user, PROP_HOME));
				if (home.isDirectory() && home.canRead()) {
					//??????????????????????????????????????????????????????
					return home.getCanonicalPath();
				}
			} catch (IOException e) {
			}
			return null;
		}

		//???????????????
		public boolean hasWritePerm(final String user) {
			final String value = getValue(user, PROP_ENABLE_WRITE);
			return Boolean.parseBoolean(value);
		}
	}

	//?????????????????????shell
	static class SecureShellFactory implements Factory<Command> {
		@Override
		public Command create() {
			return new SecureShellCommand();
		}
	}

	static class SecureShellCommand implements Command {
		private OutputStream err = null;
		private ExitCallback callback = null;

		@Override
		public void setInputStream(final InputStream in) {

		}

		@Override
		public void setOutputStream(final OutputStream out) {
		}

		@Override
		public void setErrorStream(final OutputStream err) {
			this.err = err;
		}

		@Override
		public void setExitCallback(final ExitCallback callback) {
			this.callback = callback;
		}

		@Override
		public void start(final Environment env) throws IOException {
			if (err != null) {
				err.write("shell not allowed\r\n".getBytes("ISO-8859-1"));
				err.flush();
			}
			if (callback != null)
				callback.onExit(-1, "shell not allowed");
		}

		@Override
		public void destroy() {
		}
	}

	/**
	 *
	 */
	static class CustomSftpSubsystemFactory extends SftpSubsystemFactory {
		@Override
		public Command create() {
			/**
			 * ??????????????? ?????????????????????????????????
			 */
			final SftpSubsystem subsystem = new SftpSubsystem(getExecutorService(), isShutdownOnExit(),
					getUnsupportedAttributePolicy()) {
				@Override
				protected void setFileAttribute(final Path file, final String view, final String attribute,
						final Object value, final LinkOption... options) throws IOException {
					throw new UnsupportedOperationException("setFileAttribute Disabled");
				}

				@Override
				protected void createLink(final int id, final String targetPath, final String linkPath,
						final boolean symLink) throws IOException {
					throw new UnsupportedOperationException("createLink Disabled");
				}
			};
			//???????????????????????????
			final Collection<? extends SftpEventListener> listeners = getRegisteredListeners();
			//???????????????????????????
			if (GenericUtils.size(listeners) > 0) {
				for (final SftpEventListener l : listeners) {
					subsystem.addSftpEventListener(l);
				}
			}
			return subsystem;
		}
	}

	//??????????????????
	static class SecureFileSystemFactory implements FileSystemFactory {
		private final Config db;

		public SecureFileSystemFactory(final Config db) {
			this.db = db;
		}

		@Override
		public FileSystem createFileSystem(final Session session) throws IOException {
			final String userName = session.getUsername();
			//????????????????????????
			final String home = db.getHome(userName);
			if (home == null) {
				throw new IOException("user home error");
			}
			//???????????????????????????????????????
			/**
			 * ????????????
			 * ???????????????
			 */
			final RootedFileSystemProvider rfsp = db.hasWritePerm(userName) ? new RootedFileSystemProvider()
					: new ReadOnlyRootedFileSystemProvider();
			return rfsp.newFileSystem(Paths.get(home), Collections.<String, Object>emptyMap());
		}
	}

	// =================== PublicKeyHelper

	static class PublicKeyHelper {
		private static final Charset US_ASCII = Charset.forName("US-ASCII");

		public static String getEncodedPublicKey(final PublicKey pub) {
			if (pub instanceof RSAPublicKey) {
				return encodeRSAPublicKey((RSAPublicKey) pub);
			}
			if (pub instanceof DSAPublicKey) {
				return encodeDSAPublicKey((DSAPublicKey) pub);
			}
			return null;
		}

		public static String encodeRSAPublicKey(final RSAPublicKey key) {
			final BigInteger[] params = new BigInteger[] {
					key.getPublicExponent(), key.getModulus()
			};
			return encodePublicKey(params, "ssh-rsa");
		}

		public static String encodeDSAPublicKey(final DSAPublicKey key) {
			final BigInteger[] params = new BigInteger[] {
					key.getParams().getP(), key.getParams().getQ(), key.getParams().getG(), key.getY()
			};
			return encodePublicKey(params, "ssh-dss");
		}

		private static final void encodeUInt32(final IoBuffer bab, final int value) {
			bab.put((byte) ((value >> 24) & 0xFF));
			bab.put((byte) ((value >> 16) & 0xFF));
			bab.put((byte) ((value >> 8) & 0xFF));
			bab.put((byte) (value & 0xFF));
		}

		private static String encodePublicKey(final BigInteger[] params, final String keyType) {
			final IoBuffer bab = IoBuffer.allocate(256);
			bab.setAutoExpand(true);
			byte[] buf = null;
			// encode the header "ssh-dss" / "ssh-rsa"
			buf = keyType.getBytes(US_ASCII); // RFC-4253, pag.13
			encodeUInt32(bab, buf.length);    // RFC-4251, pag.8 (string encoding)
			for (final byte b : buf) {
				bab.put(b);
			}
			// encode params
			for (final BigInteger param : params) {
				buf = param.toByteArray();
				encodeUInt32(bab, buf.length);
				for (final byte b : buf) {
					bab.put(b);
				}
			}
			bab.flip();
			buf = new byte[bab.limit()];
			System.arraycopy(bab.array(), 0, buf, 0, buf.length);
			bab.free();
			return keyType + " " + DatatypeConverter.printBase64Binary(buf);
		}
	}

	/**
	 * Spring?????????????????????Bean????????????????????????????????????????????????@Autowired????????????????????????????????????????????????
	 * ?????????????????????userService??????
	 * @param userService
	 */

	@Autowired(required = true)
	public  void setUserService(UserService userService) {
		Server.userService = userService;
	}

}
