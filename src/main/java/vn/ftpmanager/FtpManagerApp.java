package vn.ftpmanager;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Spinner;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import vn.ftpmanager.data.AccountRepository;
import vn.ftpmanager.data.FtpAccount;
import vn.ftpmanager.ftp.FtpServerService;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class FtpManagerApp extends Application {
    private final FtpServerService serverService = new FtpServerService();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ftp-manager-worker");
        thread.setDaemon(true);
        return thread;
    });
    private AccountRepository repository;
    private FileTransferView transferView;
    private TableView<FtpAccount> accountTable;
    private Label statusValue;
    private Label statusDot;
    private Label accountCount;
    private Label activityText;
    private Spinner<Integer> portSpinner;
    private Button toggleButton;
    private boolean serverMode;
    private boolean clientMode;

    @Override
    public void start(Stage stage) throws Exception {
        String mode = getParameters().getRaw().stream().findFirst().orElse("client").toLowerCase();
        if (!List.of("server", "client", "both").contains(mode)) {
            throw new IllegalArgumentException("Mode must be server, client, or both");
        }
        serverMode = mode.equals("server") || mode.equals("both");
        clientMode = mode.equals("client") || mode.equals("both");
        if (serverMode) {
            Path homeDirectory = Path.of(System.getProperty("user.home"));
            Path appDirectory = homeDirectory.resolve("ftp-manager-data");
            Path legacyDatabase = homeDirectory.resolve(".ftp-manager").resolve("ftp-manager.db");
            repository = new AccountRepository(appDirectory, legacyDatabase);
        }

        BorderPane root = new BorderPane();
        root.getStyleClass().add("app-shell");
        root.setTop(buildHeader(mode));
        ScrollPane serverPane = null;
        if (serverMode) {
            serverPane = new ScrollPane(buildContent());
            serverPane.setFitToWidth(true);
            serverPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            serverPane.getStyleClass().add("content-scroll");
        }
        if (clientMode) {
            transferView = new FileTransferView();
        }
        if (serverMode && clientMode) {
            Tab transferTab = new Tab("File Transfer", transferView);
            Tab serverTab = new Tab("Server Admin", serverPane);
            transferTab.setClosable(false);
            serverTab.setClosable(false);
            TabPane workspaceTabs = new TabPane(transferTab, serverTab);
            workspaceTabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
            workspaceTabs.getStyleClass().add("workspace-tabs");
            root.setCenter(workspaceTabs);
        } else {
            root.setCenter(clientMode ? transferView : serverPane);
        }
        Scene scene = new Scene(root, 1400, 850);
        scene.getStylesheets().add(getClass().getResource("/styles.css").toExternalForm());
        stage.setTitle("FTP Manager - " + (mode.equals("both") ? "Client and Server" : mode));
        stage.setMinWidth(980);
        stage.setMinHeight(620);
        stage.setScene(scene);
        stage.setMaximized(true);
        stage.setOnCloseRequest(event -> {
            try {
                serverService.stop();
            } catch (Exception ignored) {
            }
            if (transferView != null) {
                transferView.shutdown();
            }
            worker.shutdownNow();
        });
        if (serverMode) {
            refreshAccounts();
        }
        stage.show();
    }

    private HBox buildHeader(String mode) {
        Label mark = new Label("F");
        mark.getStyleClass().add("brand-mark");
        VBox brandCopy = new VBox(2, new Label("FILE TRANSFER"), new Label("FTP WORKSPACE"));
        brandCopy.getStyleClass().add("brand-copy");
        HBox brand = new HBox(12, mark, brandCopy);
        brand.setAlignment(Pos.CENTER_LEFT);

        Label section = new Label(mode.equals("both") ? "Client  /  Server" : mode.toUpperCase() + " MODE");
        section.getStyleClass().add("header-section");
        HBox header = new HBox(34, brand, section);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(10, 24, 10, 24));
        header.getStyleClass().add("top-bar");
        return header;
    }

    private VBox buildContent() {
        Label eyebrow = new Label("CONTROL ROOM  /  FTP SERVICE");
        eyebrow.getStyleClass().add("eyebrow");
        Label title = new Label("FTP server administration");
        title.getStyleClass().add("page-title");
        Label subtitle = new Label("Manage accounts, storage access, and server status.");
        subtitle.getStyleClass().add("subtitle");
        VBox intro = new VBox(4, eyebrow, title, subtitle);
        intro.getStyleClass().add("intro");

        HBox statusCard = buildServerCard();
        HBox metrics = buildMetrics();
        VBox accounts = buildAccountsPanel();
        HBox activity = buildActivityPanel();
        VBox content = new VBox(12, intro, statusCard, metrics, accounts, activity);
        content.setPadding(new Insets(18, 24, 20, 24));
        content.getStyleClass().add("main-content");
        return content;
    }

    private HBox buildServerCard() {
        statusDot = new Label();
        statusDot.getStyleClass().addAll("status-dot", "is-offline");
        statusValue = new Label("Stopped");
        statusValue.getStyleClass().add("server-state");
        Label heading = new Label("FTP SERVER");
        heading.getStyleClass().add("eyebrow");
        Label lanInstructions = new Label(
            "Run hostname -I in Terminal; use the Wi-Fi/Ethernet IPv4 on the client.");
        lanInstructions.getStyleClass().add("lan-instructions");
        lanInstructions.setWrapText(true);
        VBox state = new VBox(7, heading, new HBox(9, statusDot, statusValue),
            lanInstructions);

        Label portLabel = new Label("PORT");
        portLabel.getStyleClass().add("field-label");
        portSpinner = new Spinner<>(1024, 65535, 2121);
        portSpinner.setEditable(true);
        portSpinner.getStyleClass().add("port-spinner");
        Label passivePortsLabel = new Label("DATA 50000-50100");
        passivePortsLabel.getStyleClass().add("field-label");
        VBox portControl = new VBox(7, portLabel, portSpinner, passivePortsLabel);

        toggleButton = new Button("Start server");
        toggleButton.getStyleClass().add("primary-button");
        toggleButton.setOnAction(event -> toggleServer());
        HBox card = new HBox(26, state, portControl, toggleButton);
        card.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(state, Priority.ALWAYS);
        card.getStyleClass().add("server-card");
        card.setPadding(new Insets(12, 18, 12, 18));
        return card;
    }

    private HBox buildMetrics() {
        accountCount = new Label("0");
        accountCount.getStyleClass().add("metric-value");
        Label countCaption = new Label("Accounts with access");
        countCaption.getStyleClass().add("metric-caption");
        VBox users = metric("ACCESS", accountCount, countCaption);

        Label protocolValue = new Label("FTP");
        protocolValue.getStyleClass().add("metric-value");
        VBox protocol = metric("PROTOCOL", protocolValue, new Label("Apache MINA engine"));

        Label storageValue = new Label("SQLite");
        storageValue.getStyleClass().addAll("metric-value", "metric-word");
        VBox storage = metric("ACCOUNT STORAGE", storageValue,
            new Label("~/ftp-manager-data/ftp-manager.db"));

        HBox metrics = new HBox(0, users, protocol, storage);
        metrics.getStyleClass().add("metrics-row");
        return metrics;
    }

    private VBox metric(String labelText, Label value, Label caption) {
        Label label = new Label(labelText);
        label.getStyleClass().add("eyebrow");
        caption.getStyleClass().add("metric-caption");
        VBox box = new VBox(5, label, value, caption);
        box.getStyleClass().add("metric");
        HBox.setHgrow(box, Priority.ALWAYS);
        return box;
    }

    private VBox buildAccountsPanel() {
        Label title = new Label("FTP accounts");
        title.getStyleClass().add("panel-title");
        Label caption = new Label("All authenticated accounts can access the shared FTP folder.");
        caption.getStyleClass().add("panel-caption");
        Button addButton = new Button("+  Add account");
        addButton.getStyleClass().add("secondary-button");
        addButton.setOnAction(event -> showAddAccountDialog());
        HBox panelHeader = new HBox(12, new VBox(5, title, caption), addButton);
        panelHeader.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(panelHeader.getChildren().get(0), Priority.ALWAYS);

        accountTable = new TableView<>();
        accountTable.setPlaceholder(new Label("No accounts yet. Add one to allow FTP connections."));
        accountTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        TableColumn<FtpAccount, String> userColumn = new TableColumn<>("USERNAME");
        userColumn.setCellValueFactory(cell -> new javafx.beans.property.SimpleStringProperty(
            cell.getValue().username()));
        TableColumn<FtpAccount, String> folderColumn = new TableColumn<>("STORAGE");
        folderColumn.setCellValueFactory(cell -> new javafx.beans.property.SimpleStringProperty("Shared"));
        TableColumn<FtpAccount, String> accessColumn = new TableColumn<>("ACCESS");
        accessColumn.setCellValueFactory(cell -> new javafx.beans.property.SimpleStringProperty(
                cell.getValue().enabled() ? "Enabled" : "Disabled"));
        accountTable.getColumns().addAll(userColumn, folderColumn, accessColumn);
        accountTable.getStyleClass().add("accounts-table");
        accountTable.setMinHeight(95);
        accountTable.setMaxHeight(280);

        Button deleteButton = new Button("Remove selected");
        deleteButton.getStyleClass().add("text-button");
        deleteButton.setOnAction(event -> removeSelectedAccount());
        HBox footer = new HBox(deleteButton);
        footer.setAlignment(Pos.CENTER_RIGHT);

        VBox panel = new VBox(10, panelHeader, accountTable, footer);
        panel.getStyleClass().add("panel");
        panel.setPadding(new Insets(14));
        return panel;
    }

    private HBox buildActivityPanel() {
        Label title = new Label("LATEST ACTIVITY");
        title.getStyleClass().add("eyebrow");
        activityText = new Label("Ready when you are.");
        activityText.getStyleClass().add("activity-message");
        HBox panel = new HBox(12, title, activityText);
        panel.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(activityText, Priority.ALWAYS);
        panel.getStyleClass().addAll("panel", "activity-panel");
        panel.setPadding(new Insets(9, 14, 9, 14));
        return panel;
    }

    private void toggleServer() {
        toggleButton.setDisable(true);
        if (serverService.isRunning()) {
            worker.execute(() -> {
                try {
                    serverService.stop();
                    Platform.runLater(() -> setServerState(false, "Server stopped"));
                } catch (Exception exception) {
                    Platform.runLater(() -> showError(exception));
                } finally {
                    Platform.runLater(() -> toggleButton.setDisable(false));
                }
            });
            return;
        }
        int port = portSpinner.getValue();
        worker.execute(() -> {
            try {
                serverService.start(port, repository);
                Platform.runLater(() -> setServerState(true, "Listening on port " + port));
            } catch (Exception exception) {
                Platform.runLater(() -> showError(exception));
            } finally {
                Platform.runLater(() -> toggleButton.setDisable(false));
            }
        });
    }

    private void setServerState(boolean running, String event) {
        statusValue.setText(running ? "Running" : "Stopped");
        statusDot.getStyleClass().setAll("status-dot", running ? "is-online" : "is-offline");
        toggleButton.setText(running ? "Stop server" : "Start server");
        toggleButton.getStyleClass().setAll(running ? "danger-button" : "primary-button");
        portSpinner.setDisable(running);
        recordActivity(event);
    }

    private void showAddAccountDialog() {
        TextField username = new TextField();
        username.setPromptText("e.g. linh.nguyen");
        PasswordField password = new PasswordField();
        password.setPromptText("At least 8 characters");
        VBox form = new VBox(9, fieldLabel("USERNAME"), username, fieldLabel("PASSWORD"), password);
        form.setPadding(new Insets(12, 0, 0, 0));
        Alert dialog = new Alert(Alert.AlertType.CONFIRMATION);
        dialog.setTitle("New FTP account");
        dialog.setHeaderText("Create an account");
        dialog.getDialogPane().setContent(form);
        dialog.getButtonTypes().setAll(new javafx.scene.control.ButtonType("Create"),
                javafx.scene.control.ButtonType.CANCEL);
        dialog.showAndWait().filter(button -> button.getText().equals("Create")).ifPresent(button -> {
            char[] secret = password.getText().toCharArray();
            try {
                repository.create(username.getText(), secret);
                refreshAccounts();
                recordActivity("Account " + username.getText().trim() + " created");
            } catch (Exception exception) {
                showError(exception);
            } finally {
                java.util.Arrays.fill(secret, '\0');
                password.clear();
            }
        });
    }

    private Label fieldLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("field-label");
        return label;
    }

    private void removeSelectedAccount() {
        FtpAccount selected = accountTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Remove FTP access for " + selected.username() + "? Its home folder will be kept.");
        confirm.setHeaderText("Remove account");
        confirm.showAndWait().filter(button -> button == javafx.scene.control.ButtonType.OK).ifPresent(button -> {
            try {
                repository.delete(selected.username());
                refreshAccounts();
                recordActivity("Account " + selected.username() + " removed");
            } catch (SQLException exception) {
                showError(exception);
            }
        });
    }

    private void refreshAccounts() {
        try {
            List<FtpAccount> accounts = repository.findAll();
            accountTable.setItems(FXCollections.observableArrayList(accounts));
            accountCount.setText(Integer.toString(accounts.size()));
            int visibleRows = Math.max(2, Math.min(7, accounts.size()));
            accountTable.setPrefHeight(32 + visibleRows * 34);
        } catch (SQLException exception) {
            showError(exception);
        }
    }

    private void recordActivity(String event) {
        activityText.setText(event + "  ·  " + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")));
    }

    private void showError(Exception exception) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("FTP Manager");
        alert.setHeaderText("Could not complete the action");
        alert.setContentText(exception.getMessage() == null ? exception.toString() : exception.getMessage());
        alert.showAndWait();
    }

    @Override
    public void stop() throws Exception {
        serverService.stop();
        if (transferView != null) {
            transferView.shutdown();
        }
        worker.shutdownNow();
        super.stop();
    }

    public static void main(String[] args) {
        launch(args);
    }
}