package vn.ftpmanager.ftp;

import org.apache.ftpserver.FtpServer;
import org.apache.ftpserver.FtpServerFactory;
import org.apache.ftpserver.DataConnectionConfigurationFactory;
import org.apache.ftpserver.ftplet.FtpException;
import org.apache.ftpserver.listener.ListenerFactory;
import vn.ftpmanager.data.AccountRepository;

public final class FtpServerService {
    private FtpServer server;

    public synchronized void start(int port, AccountRepository repository) throws FtpException {
        if (server != null && !server.isStopped()) {
            throw new IllegalStateException("FTP server is already running");
        }
        FtpServerFactory serverFactory = new FtpServerFactory();
        ListenerFactory listenerFactory = new ListenerFactory();
        DataConnectionConfigurationFactory dataConfiguration = new DataConnectionConfigurationFactory();
        dataConfiguration.setPassivePorts("50000-50100");
        listenerFactory.setPort(port);
        listenerFactory.setDataConnectionConfiguration(dataConfiguration.createDataConnectionConfiguration());
        serverFactory.addListener("default", listenerFactory.createListener());
        serverFactory.setUserManager(new DatabaseUserManager(repository));
        FtpServer newServer = serverFactory.createServer();
        newServer.start();
        server = newServer;
    }

    public synchronized void stop() throws FtpException {
        if (server != null && !server.isStopped()) {
            server.stop();
        }
        server = null;
    }

    public synchronized boolean isRunning() {
        return server != null && !server.isStopped();
    }
}