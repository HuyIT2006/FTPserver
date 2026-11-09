package vn.ftpmanager.ftp;

import org.apache.ftpserver.ftplet.Authentication;
import org.apache.ftpserver.ftplet.AuthenticationFailedException;
import org.apache.ftpserver.ftplet.FtpException;
import org.apache.ftpserver.ftplet.User;
import org.apache.ftpserver.ftplet.UserManager;
import org.apache.ftpserver.usermanager.UsernamePasswordAuthentication;
import org.apache.ftpserver.usermanager.impl.BaseUser;
import org.apache.ftpserver.usermanager.impl.ConcurrentLoginPermission;
import org.apache.ftpserver.usermanager.impl.TransferRatePermission;
import org.apache.ftpserver.usermanager.impl.WritePermission;
import vn.ftpmanager.data.AccountRepository;
import vn.ftpmanager.data.FtpAccount;

import java.io.IOException;
import java.util.List;

public final class DatabaseUserManager implements UserManager {
    private final AccountRepository repository;

    public DatabaseUserManager(AccountRepository repository) {
        this.repository = repository;
    }

    @Override
    public User getUserByName(String username) throws FtpException {
        try {
            FtpAccount account = repository.find(username);
            return account == null || !account.enabled() ? null : toFtpUser(account);
        } catch (Exception exception) {
            throw new FtpException("Could not read FTP account", exception);
        }
    }

    @Override
    public String[] getAllUserNames() throws FtpException {
        try {
            return repository.findAll().stream().map(FtpAccount::username).toArray(String[]::new);
        } catch (Exception exception) {
            throw new FtpException("Could not list FTP accounts", exception);
        }
    }

    @Override
    public void save(User user) throws FtpException {
        throw new FtpException("Manage FTP accounts in the application");
    }

    @Override
    public void delete(String username) throws FtpException {
        try {
            repository.delete(username);
        } catch (Exception exception) {
            throw new FtpException("Could not delete FTP account", exception);
        }
    }

    @Override
    public boolean doesExist(String username) throws FtpException {
        return getUserByName(username) != null;
    }

    @Override
    public User authenticate(Authentication authentication) throws AuthenticationFailedException {
        if (!(authentication instanceof UsernamePasswordAuthentication credentials)) {
            throw new AuthenticationFailedException("Unsupported authentication method");
        }
        try {
            if (!repository.authenticate(credentials.getUsername(), credentials.getPassword())) {
                throw new AuthenticationFailedException("Invalid username or password");
            }
            FtpAccount account = repository.find(credentials.getUsername());
            return account == null ? null : toFtpUser(account);
        } catch (AuthenticationFailedException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new AuthenticationFailedException("Could not verify credentials", exception);
        }
    }

    @Override
    public String getAdminName() {
        return null;
    }

    @Override
    public boolean isAdmin(String username) {
        return false;
    }

    private User toFtpUser(FtpAccount account) {
        BaseUser user = new BaseUser();
        user.setName(account.username());
        user.setEnabled(account.enabled());
        user.setHomeDirectory(repository.getSharedStoragePath().toAbsolutePath().toString());
        user.setAuthorities(List.of(new WritePermission(), new ConcurrentLoginPermission(50, 20),
                new TransferRatePermission(0, 0)));
        return user;
    }
}