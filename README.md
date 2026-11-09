# FTP Manager

A small JavaFX FTP workspace with a FileZilla-style client and an embedded FTP server manager backed by SQLite.

## Requirements

- JDK 21
- Maven 3.8+

## Run

Run only the role required on each computer:

Server computer:

```bash
mvn javafx:run -Dapp.mode=server
```

Each client computer:

```bash
mvn javafx:run -Dapp.mode=client
```

For a one-computer development demo with both tabs, use:

```bash
mvn javafx:run -Dapp.mode=both
```

The client mode does not initialize a local server or database. It connects to the server computer using its LAN IPv4 address, username, and password. Multiple client computers can connect at the same time; each account has separate credentials, while all authenticated accounts access the same shared FTP folder.

The **File Transfer** interface provides local and remote file browsers, upload/download actions, and a transfer queue. In `both` mode, create an account in **Server Admin**, start the server on port `2121`, then connect to `127.0.0.1` with that account.

## Demo on two computers on the same LAN

1. Connect both computers to the same Wi-Fi or wired network.
2. On the server computer, open Terminal and run `hostname -I`. Choose the Wi-Fi or Ethernet IPv4 address, not `127.0.0.1`. The application does not detect or fetch this address. Create an FTP account and start the server.
3. Allow inbound TCP ports `2121` and `50000-50100` in the server computer's firewall. For Ubuntu with UFW:

	```bash
	sudo ufw allow 2121/tcp
	sudo ufw allow 50000:50100/tcp
	```

4. On the client computer, open **File Transfer** and connect to the server's LAN IPv4 address on port `2121`, using the account created on the server computer.
5. In FileZilla, use FTP with plain FTP encryption and passive transfer mode. Upload and download a test file to verify the data ports are reachable.

No router port forwarding is needed for computers on the same LAN. If the second computer cannot connect, check that the Wi-Fi access point does not enable client isolation and that both computers are on the same subnet.

The live database is `~/ftp-manager-data/ftp-manager.db`, which can be opened directly in DB Browser for SQLite. On first server launch, existing accounts are copied from the old `~/.ftp-manager/ftp-manager.db` automatically. Existing account files are copied, without overwriting files, into username-named subfolders under `~/ftp-manager-data/shared/`; all clients can browse and download from the shared root. Stop any older server process before launching the updated server so new accounts are written to the same database.

The server uses plain FTP, which does not encrypt credentials or file contents. Keep it on a trusted local network for this learning project; do not expose it directly to the public internet.