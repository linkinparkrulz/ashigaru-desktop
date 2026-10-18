package com.sparrowwallet.sparrow.preferences;

import com.github.arteam.simplejsonrpc.client.exception.JsonRpcException;
import com.google.common.base.Throwables;
import com.google.common.eventbus.Subscribe;
import com.google.common.net.HostAndPort;
import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.sparrow.AppServices;
import com.sparrowwallet.sparrow.EventManager;
import com.sparrowwallet.sparrow.Mode;
import com.sparrowwallet.sparrow.control.ComboBoxTextField;
import com.sparrowwallet.sparrow.control.TextFieldValidator;
import com.sparrowwallet.sparrow.control.UnlabeledToggleSwitch;
import com.sparrowwallet.sparrow.event.*;
import com.sparrowwallet.sparrow.glyphfont.FontAwesome5;
import com.sparrowwallet.sparrow.io.Config;
import com.sparrowwallet.sparrow.io.Server;
import com.sparrowwallet.sparrow.io.Storage;
import com.sparrowwallet.sparrow.net.*;
import com.sparrowwallet.sparrow.net.dojo.DojoNodeDiscovery;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.controlsfx.control.SegmentedButton;
import org.controlsfx.glyphfont.Glyph;
import org.controlsfx.validation.ValidationResult;
import org.controlsfx.validation.ValidationSupport;
import org.controlsfx.validation.Validator;
import org.controlsfx.validation.decoration.StyleClassValidationDecoration;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tornadofx.control.Field;
import tornadofx.control.Form;

import java.io.File;
import java.io.FileInputStream;
import java.security.cert.CertificateFactory;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Collectors;

public class ServerPreferencesController extends PreferencesDetailController {
    private static final Logger log = LoggerFactory.getLogger(ServerPreferencesController.class);

    private static final Server MANAGE_ALIASES_SERVER = new Server("tcp://localhost", "Manage Aliases...");

    @FXML
    private GridPane serverDetailPane;

    @FXML
    private Label warningBodyShort;

    @FXML
    private Label warningBodyLong;

    @FXML
    private VBox publicServerWarningBox;

    @FXML
    private ToggleGroup serverTypeToggleGroup;

    @FXML
    private SegmentedButton serverTypeSegmentedButton;

    @FXML
    private ToggleButton publicElectrumToggle;

    @FXML
    private Form publicElectrumForm;

    @FXML
    private ComboBox<Server> publicElectrumServer;

    @FXML
    private UnlabeledToggleSwitch publicUseProxy;

    @FXML
    private TextField publicProxyHost;

    @FXML
    private TextField publicProxyPort;

    @FXML
    private Form coreForm;

    @FXML
    private ComboBox<Server> recentCoreServers;

    @FXML
    private ComboBoxTextField coreHost;

    @FXML
    private TextField corePort;

    @FXML
    private ToggleGroup coreAuthToggleGroup;

    @FXML
    private Field coreDataDirField;

    @FXML
    private TextField coreDataDir;

    @FXML
    private Button coreDataDirSelect;

    @FXML
    private Field coreUserPassField;

    @FXML
    private TextField coreUser;

    @FXML
    private PasswordField corePass;

    @FXML
    private UnlabeledToggleSwitch coreUseProxy;

    @FXML
    private TextField coreProxyHost;

    @FXML
    private TextField coreProxyPort;

    @FXML
    private Form electrumForm;

    @FXML
    private ComboBox<Server> recentElectrumServers;

    @FXML
    private Hyperlink discoverNodesLink;

    @FXML
    private Label discoverNodesStatus;

    @FXML
    private ComboBoxTextField electrumHost;

    @FXML
    private TextField electrumPort;

    @FXML
    private UnlabeledToggleSwitch electrumUseSsl;

    @FXML
    private TextField electrumCertificate;

    @FXML
    private Button electrumCertificateSelect;

    @FXML
    private UnlabeledToggleSwitch useProxy;

    @FXML
    private TextField proxyHost;

    @FXML
    private TextField proxyPort;

    @FXML
    private Button testConnection;

    @FXML
    private Button editConnection;

    @FXML
    private Button restartTor;

    private boolean restartingTor;

    @FXML
    private TextArea testResults;

    private final ValidationSupport validationSupport = new ValidationSupport();

    private TorService torService;

    private ElectrumServer.ConnectionService connectionService;

    private Boolean useSslOriginal;

    private Boolean useProxyOriginal;

    @Override
    public void initializeView(Config config) {
        EventManager.get().register(this);

        //These labels wrap but were still ellipsizing — that is a HEIGHT problem, not a width one. A
        //wrapText Label asked for its preferred height without a width (prefHeight(-1)) reports the
        //height of a SINGLE line, so the enclosing row/field only allocates one line of vertical
        //space; the label then wraps at its real width and everything past that is clipped. Setting
        //minHeight to USE_PREF_SIZE makes the parent query minHeight(width) at the actual layout
        //width, which returns the true wrapped height and forces the warning box to grow.
        warningBodyShort.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        warningBodyLong.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        discoverNodesStatus.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        //The box itself too, or the field row keeps the height of a single line and the warning
        //paints over the URL field below it rather than pushing it down. Not null-guarded: it is an
        //@FXML node of server.fxml, the only FXML this controller backs, and the wrap width below is
        //bound to it - if it ever went missing that should fail here rather than silently mis-wrap.
        publicServerWarningBox.setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);

        //Wrap at the width the text is actually laid out at, which is the warning box itself. The
        //minHeight above only returns a true height when the width it measures at is the width the
        //label ends up with; if the two disagree the row is sized for one wrap and the text wraps at
        //another, and the difference overflows the row. Nothing clips it - JavaFX Regions do not clip
        //children - and the input container centres its content vertically, so an over-tall box
        //spills over the fields both below AND above it.
        //
        //This was previously derived from the scene width less a constant standing in for the
        //sidebars and padding. That is a guess at the column width rather than the column width: at
        //the 800px minimum window it resolved to roughly twice the space the labels really get, and
        //the warning painted over the server type and URL rows.
        //
        //The box is a maxWidth=Infinity VBox filling the input column, so its width is exactly the
        //space available, less its own 12+12 padding. Its width comes from its parent and the labels
        //only affect height, so there is no horizontal feedback. discoverNodesStatus sits in an
        //identical input column a row down and takes the same width. The floor only covers the frame
        //before first layout, where the box still reports zero.
        javafx.beans.binding.NumberBinding wrapWidth =
                javafx.beans.binding.Bindings.max(120, publicServerWarningBox.widthProperty().subtract(24));
        warningBodyShort.maxWidthProperty().bind(wrapWidth);
        warningBodyLong.maxWidthProperty().bind(wrapWidth);
        discoverNodesStatus.maxWidthProperty().bind(wrapWidth);

        getMasterController().closingProperty().addListener((observable, oldValue, newValue) -> {
            EventManager.get().unregister(this);
            if(connectionService != null && connectionService.isRunning()) {
                connectionService.cancel();
            }
        });

        Platform.runLater(this::setupValidation);

        publicElectrumForm.managedProperty().bind(publicElectrumForm.visibleProperty());
        if(coreForm != null) coreForm.managedProperty().bind(coreForm.visibleProperty());
        electrumForm.managedProperty().bind(electrumForm.visibleProperty());
        serverTypeToggleGroup.selectedToggleProperty().addListener((observable, oldValue, newValue) -> {
            if(serverTypeToggleGroup.getSelectedToggle() != null) {
                ServerType existingType = config.getServerType();
                ServerType serverType = (ServerType)newValue.getUserData();
                publicElectrumForm.setVisible(serverType == ServerType.PUBLIC_ELECTRUM_SERVER);
                if(coreForm != null) coreForm.setVisible(serverType == ServerType.BITCOIN_CORE);
                electrumForm.setVisible(serverType == ServerType.ELECTRUM_SERVER);
                config.setServerType(serverType);
                testConnection.setGraphic(getGlyph(FontAwesome5.Glyph.QUESTION_CIRCLE, ""));
                testResults.clear();
                if(existingType != serverType) {
                    EventManager.get().post(new ServerTypeChangedEvent(serverType));
                }
            } else if(oldValue != null) {
                oldValue.setSelected(true);
            }
        });
        ServerType serverType = config.getServerType() != null && config.getServerType() != ServerType.BITCOIN_CORE ?
                (config.getServerType() == ServerType.PUBLIC_ELECTRUM_SERVER && !PublicElectrumServer.supportedNetwork() ? ServerType.ELECTRUM_SERVER : config.getServerType()) :
                    (config.getElectrumServer() != null ? ServerType.ELECTRUM_SERVER :
                        (!PublicElectrumServer.supportedNetwork() ? ServerType.ELECTRUM_SERVER : ServerType.PUBLIC_ELECTRUM_SERVER));
        if(!PublicElectrumServer.supportedNetwork()) {
            serverTypeSegmentedButton.getButtons().remove(publicElectrumToggle);
            serverTypeToggleGroup.getToggles().remove(publicElectrumToggle);
        }
        serverTypeToggleGroup.selectToggle(serverTypeToggleGroup.getToggles().stream().filter(toggle -> toggle.getUserData() == serverType).findFirst().orElse(null));

        publicElectrumServer.setItems(getPublicServerList());
        publicElectrumServer.setCellFactory(value -> new ServerCell());
        publicElectrumServer.setButtonCell(new ServerCell());
        publicElectrumServer.getSelectionModel().selectedItemProperty().addListener(getPublicElectrumServerListener(config));

        publicUseProxy.selectedProperty().bindBidirectional(useProxy.selectedProperty());
        publicProxyHost.textProperty().bindBidirectional(proxyHost.textProperty());
        publicProxyPort.textProperty().bindBidirectional(proxyPort.textProperty());

        if(corePort != null) corePort.setTextFormatter(new TextFieldValidator(TextFieldValidator.ValidationModus.MAX_INTEGERS, 5).getFormatter());
        electrumPort.setTextFormatter(new TextFieldValidator(TextFieldValidator.ValidationModus.MAX_INTEGERS, 5).getFormatter());
        proxyPort.setTextFormatter(new TextFieldValidator(TextFieldValidator.ValidationModus.MAX_INTEGERS, 5).getFormatter());
        if(coreProxyPort != null) coreProxyPort.setTextFormatter(new TextFieldValidator(TextFieldValidator.ValidationModus.MAX_INTEGERS, 5).getFormatter());
        publicProxyPort.setTextFormatter(new TextFieldValidator(TextFieldValidator.ValidationModus.MAX_INTEGERS, 5).getFormatter());

        if(coreHost != null) coreHost.textProperty().addListener(getBitcoinCoreListener(config));
        if(corePort != null) corePort.textProperty().addListener(getBitcoinCoreListener(config));

        if(coreUser != null) coreUser.textProperty().addListener(getBitcoinAuthListener(config));
        if(corePass != null) corePass.textProperty().addListener(getBitcoinAuthListener(config));

        if(coreUseProxy != null) coreUseProxy.selectedProperty().bindBidirectional(useProxy.selectedProperty());
        if(coreProxyHost != null) coreProxyHost.textProperty().bindBidirectional(proxyHost.textProperty());
        if(coreProxyPort != null) coreProxyPort.textProperty().bindBidirectional(proxyPort.textProperty());

        electrumHost.textProperty().addListener(getElectrumServerListener(config));
        electrumPort.textProperty().addListener(getElectrumServerListener(config));

        proxyHost.textProperty().addListener(getProxyListener(config));
        proxyPort.textProperty().addListener(getProxyListener(config));

        if(coreForm != null) {
            corePort.setPromptText("e.g. " + Network.get().getDefaultPort());
            coreDataDirField.managedProperty().bind(coreDataDirField.visibleProperty());
            coreUserPassField.managedProperty().bind(coreUserPassField.visibleProperty());
            coreUserPassField.visibleProperty().bind(coreDataDirField.visibleProperty().not());
            coreAuthToggleGroup.selectedToggleProperty().addListener((observable, oldValue, newValue) -> {
                if(coreAuthToggleGroup.getSelectedToggle() != null) {
                    CoreAuthType coreAuthType = (CoreAuthType)newValue.getUserData();
                    coreDataDirField.setVisible(coreAuthType == CoreAuthType.COOKIE);
                    config.setCoreAuthType(coreAuthType);
                } else if(oldValue != null) {
                    oldValue.setSelected(true);
                }
            });
            CoreAuthType coreAuthType = config.getCoreAuthType() != null ? config.getCoreAuthType() : CoreAuthType.COOKIE;
            coreAuthToggleGroup.selectToggle(coreAuthToggleGroup.getToggles().stream().filter(toggle -> toggle.getUserData() == coreAuthType).findFirst().orElse(null));

            coreDataDir.textProperty().addListener((observable, oldValue, newValue) -> {
                File dataDir = getDirectory(newValue);
                config.setCoreDataDir(dataDir);
            });

            coreDataDirSelect.setOnAction(event -> {
                Stage window = new Stage();

                DirectoryChooser directorChooser = new DirectoryChooser();
                directorChooser.setTitle("Select Bitcoin Core Data Directory");
                directorChooser.setInitialDirectory(config.getCoreDataDir() != null ? config.getCoreDataDir() : new File(System.getProperty("user.home")));

                File dataDir = directorChooser.showDialog(window);
                if(dataDir != null) {
                    coreDataDir.setText(dataDir.getAbsolutePath());
                }
            });
        }

        if(recentCoreServers != null) {
            recentCoreServers.setCellFactory(value -> new ServerCell());
            recentCoreServers.setItems(getObservableServerList(Config.get().getRecentCoreServers()));
            recentCoreServers.prefWidthProperty().bind(coreHost.widthProperty());
            recentCoreServers.valueProperty().addListener((observable, oldValue, newValue) -> {
                if(newValue != null) {
                    if(newValue == MANAGE_ALIASES_SERVER) {
                        ServerAliasDialog serverAliasDialog = new ServerAliasDialog(ServerType.BITCOIN_CORE);
                        serverAliasDialog.initOwner(recentCoreServers.getScene().getWindow());
                        Optional<Server> optServer = serverAliasDialog.showAndWait();
                        recentCoreServers.setItems(getObservableServerList(Config.get().getRecentCoreServers()));
                        Server selectedServer = optServer.orElseGet(() -> Config.get().getCoreServer());
                        Platform.runLater(() -> recentCoreServers.setValue(selectedServer));
                    } else if(newValue.getHostAndPort() != null) {
                        HostAndPort hostAndPort = newValue.getHostAndPort();
                        corePort.setText(hostAndPort.hasPort() ? Integer.toString(hostAndPort.getPort()) : "");
                        if(newValue.getAlias() != null) {
                            coreHost.setText(newValue.getAlias());
                        } else {
                            coreHost.setText(hostAndPort.getHost());
                        }
                        coreHost.positionCaret(coreHost.getText().length());
                    }
                }
            });
        }

        recentElectrumServers.setCellFactory(value -> new ServerCell());
        recentElectrumServers.setItems(getObservableServerList(Config.get().getRecentElectrumServers()));
        recentElectrumServers.prefWidthProperty().bind(electrumHost.widthProperty());
        recentElectrumServers.valueProperty().addListener((observable, oldValue, newValue) -> {
            if(newValue != null) {
                if(newValue == MANAGE_ALIASES_SERVER) {
                    ServerAliasDialog serverAliasDialog = new ServerAliasDialog(ServerType.ELECTRUM_SERVER);
                    serverAliasDialog.initOwner(recentElectrumServers.getScene().getWindow());
                    Optional<Server> optServer = serverAliasDialog.showAndWait();
                    recentElectrumServers.setItems(getObservableServerList(Config.get().getRecentElectrumServers()));
                    Server selectedServer = optServer.orElseGet(() -> Config.get().getElectrumServer());
                    Platform.runLater(() -> recentElectrumServers.setValue(selectedServer));
                } else if(newValue.getHostAndPort() != null) {
                    HostAndPort hostAndPort = newValue.getHostAndPort();
                    electrumPort.setText(hostAndPort.hasPort() ? Integer.toString(hostAndPort.getPort()) : "");
                    electrumUseSsl.setSelected(newValue.getProtocol() == Protocol.SSL);
                    if(newValue.getAlias() != null) {
                        electrumHost.setText(newValue.getAlias());
                    } else {
                        electrumHost.setText(hostAndPort.getHost());
                    }
                    electrumHost.positionCaret(electrumHost.getText().length());
                }
            }
        });

        electrumUseSsl.selectedProperty().addListener((observable, oldValue, newValue) -> {
            setElectrumServerInConfig(config);
            electrumCertificate.setDisable(!newValue);
            electrumCertificateSelect.setDisable(!newValue);
            electrumPort.setPromptText(newValue ? "e.g. 50002" : "e.g. 50001");
        });

        electrumCertificate.textProperty().addListener((observable, oldValue, newValue) -> {
            File crtFile = getCertificate(newValue);
            config.setElectrumServerCert(crtFile);
        });

        electrumCertificateSelect.setOnAction(event -> {
            Stage window = new Stage();

            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle("Select Electrum Server certificate");
            fileChooser.getExtensionFilters().addAll(
                    new FileChooser.ExtensionFilter("All Files", org.controlsfx.tools.Platform.getCurrent().equals(org.controlsfx.tools.Platform.UNIX) ? "*" : "*.*"),
                    new FileChooser.ExtensionFilter("CRT", "*.crt")
            );

            AppServices.moveToActiveWindowScreen(window, 800, 450);
            File file = fileChooser.showOpenDialog(window);
            if(file != null) {
                electrumCertificate.setText(file.getAbsolutePath());
            }
        });

        useProxy.selectedProperty().addListener((observable, oldValue, newValue) -> {
            config.setUseProxy(newValue);
            proxyHost.setText(proxyHost.getText() + " ");
            proxyHost.setText(proxyHost.getText().trim());
            proxyHost.setDisable(!newValue);
            proxyPort.setDisable(!newValue);
            publicProxyHost.setDisable(!newValue);
            publicProxyPort.setDisable(!newValue);
        });

        boolean isConnected = AppServices.isConnecting() || AppServices.isConnected();

        if(Config.get().getServerType() == ServerType.BITCOIN_CORE && AppServices.isConnecting()) {
            testResults.appendText("Connecting to server, please wait...");
        }

        testConnection.managedProperty().bind(testConnection.visibleProperty());
        testConnection.setVisible(!isConnected);
        setTestResultsFont();
        testConnection.setOnAction(event -> {
            testConnection.setGraphic(getGlyph(FontAwesome5.Glyph.ELLIPSIS_H, null));
            testResults.setText("Connecting " + (config.hasServer() ? "to " + config.getServer().getUrl() : "") + "...");

            if(Config.get().requiresInternalTor() && Tor.getDefault() == null) {
                startTor();
            } else {
                startElectrumConnection();
            }
        });

        editConnection.managedProperty().bind(editConnection.visibleProperty());
        editConnection.setVisible(isConnected);
        editConnection.setDisable(Config.get().getServerType() == ServerType.BITCOIN_CORE && AppServices.isConnecting());
        editConnection.setOnAction(event -> {
            EventManager.get().post(new RequestDisconnectEvent());
            setFieldsEditable(true);
            editConnection.setVisible(false);
            testConnection.setVisible(true);
        });

        restartTor.managedProperty().bind(restartTor.visibleProperty());
        restartTor.setVisible(config.requiresInternalTor());
        restartTor.setOnAction(event -> {
            if(AppServices.get().restartTor()) {
                restartingTor = true;
                testResults.setText("Restarting Tor...");
            }
        });

        Server configPublicElectrumServer = config.getPublicElectrumServer();
        if(configPublicElectrumServer == null && PublicElectrumServer.supportedNetwork()) {
            List<PublicElectrumServer> servers = PublicElectrumServer.getServers();
            if(!servers.isEmpty()) {
                publicElectrumServer.setValue(servers.get(new Random().nextInt(servers.size())).getServer());
            }
        } else {
            publicElectrumServer.setValue(configPublicElectrumServer);
        }

        if(coreHost != null) {
            Server coreServer = config.getCoreServer();
            if(coreServer != null) {
                HostAndPort hostAndPort = coreServer.getHostAndPort();
                Server server = config.getRecentCoreServers().stream().filter(coreServer::equals).findFirst().orElse(null);
                if(server != null) {
                    coreHost.setLeft(getGlyph(FontAwesome5.Glyph.TAG, null));
                }
                coreHost.setText(server == null || server.getAlias() == null ? hostAndPort.getHost() : server.getAlias());
                if(hostAndPort.hasPort()) {
                    corePort.setText(Integer.toString(hostAndPort.getPort()));
                }
            } else {
                coreHost.setText("127.0.0.1");
                corePort.setText(String.valueOf(Network.get().getDefaultPort()));
            }

            if(coreDataDir != null) coreDataDir.setText(config.getCoreDataDir() != null ? config.getCoreDataDir().getAbsolutePath() : getDefaultCoreDataDir().getAbsolutePath());

            if(config.getCoreAuth() != null) {
                String[] userPass = config.getCoreAuth().split(":");
                if(userPass.length > 0 && coreUser != null) {
                    coreUser.setText(userPass[0]);
                }
                if(userPass.length > 1 && corePass != null) {
                    corePass.setText(userPass[1]);
                }
            }
        }

        Server electrumServer = config.getElectrumServer();
        if(electrumServer != null) {
            Protocol protocol = electrumServer.getProtocol();
            boolean ssl = protocol.equals(Protocol.SSL);
            electrumUseSsl.setSelected(ssl);
            electrumCertificate.setDisable(!ssl);
            electrumCertificateSelect.setDisable(!ssl);

            HostAndPort hostAndPort = electrumServer.getHostAndPort();
            Server server = config.getRecentElectrumServers().stream().filter(electrumServer::equals).findFirst().orElse(null);
            if(server != null) {
                electrumHost.setLeft(getGlyph(FontAwesome5.Glyph.TAG, null));
            }
            electrumHost.setText(server == null || server.getAlias() == null ? hostAndPort.getHost() : server.getAlias());
            if(hostAndPort.hasPort()) {
                electrumPort.setText(Integer.toString(hostAndPort.getPort()));
            }
        }

        File certificateFile = config.getElectrumServerCert();
        if(certificateFile != null) {
            electrumCertificate.setText(certificateFile.getAbsolutePath());
        }

        useProxy.setSelected(config.isUseProxy());
        proxyHost.setDisable(!config.isUseProxy());
        proxyPort.setDisable(!config.isUseProxy());
        publicProxyHost.setDisable(!config.isUseProxy());
        publicProxyPort.setDisable(!config.isUseProxy());

        String proxyServer = config.getProxyServer();
        if(proxyServer != null) {
            HostAndPort server = HostAndPort.fromString(proxyServer);
            proxyHost.setText(server.getHost());
            if(server.hasPort()) {
                proxyPort.setText(Integer.toString(server.getPort()));
            }
        }

        setFieldsEditable(!isConnected);
    }

    private void startTor() {
        if(torService != null && torService.isRunning()) {
            return;
        }

        torService = new TorService();
        torService.setPeriod(Duration.hours(1000));
        torService.setRestartOnFailure(false);

        torService.setOnSucceeded(workerStateEvent -> {
            Tor.setDefault(torService.getValue());
            torService.cancel();
            testResults.appendText("\nTor running, connecting to " + Config.get().getServer().getUrl() + "...");
            startElectrumConnection();
        });
        torService.setOnFailed(workerStateEvent -> {
            torService.cancel();
            testResults.appendText("\nTor failed to start");
            showConnectionFailure(workerStateEvent.getSource().getException());
        });

        torService.start();
    }

    private void startElectrumConnection() {
        if(connectionService != null && connectionService.isRunning()) {
            connectionService.cancel();
        }

        connectionService = new ElectrumServer.ConnectionService(false);
        connectionService.setPeriod(Duration.hours(1));
        connectionService.setRestartOnFailure(false);
        EventManager.get().register(connectionService);

        useSslOriginal = null;

        connectionService.setOnSucceeded(successEvent -> {
            EventManager.get().unregister(connectionService);
            ConnectionEvent connectionEvent = (ConnectionEvent)connectionService.getValue();
            showConnectionSuccess(connectionEvent.getServerVersion(), connectionEvent.getServerBanner());
            getMasterController().reconnectOnClosingProperty().set(true);
            Config.get().setMode(Mode.ONLINE);
            connectionService.cancel();
            useProxyOriginal = null;
            setFieldsEditable(false);
            testConnection.setVisible(false);
            editConnection.setVisible(true);
            Platform.runLater(() -> {
                if(!(AppServices.isConnecting() || AppServices.isConnected())) {
                    EventManager.get().post(new RequestConnectEvent());
                }
            });
            if(Config.get().addRecentServer()) {
                if(Config.get().getServerType() == ServerType.ELECTRUM_SERVER) {
                    recentElectrumServers.setItems(getObservableServerList(Config.get().getRecentElectrumServers()));
                }
            }
        });
        connectionService.setOnFailed(workerStateEvent -> {
            EventManager.get().unregister(connectionService);
            if(connectionService.isShutdown()) {
                connectionService.cancel();
                return;
            }

            showConnectionFailure(workerStateEvent.getSource().getException());
            connectionService.cancel();

            if(Config.get().getServerType() == ServerType.ELECTRUM_SERVER) {
                if(useSslOriginal == null) {
                    Integer portAsInteger = getPort(electrumPort.getText());
                    if(!electrumUseSsl.isSelected() && portAsInteger != null && portAsInteger == Protocol.SSL.getDefaultPort()) {
                        useSslOriginal = false;
                        electrumUseSsl.setSelected(true);
                    } else if(electrumUseSsl.isSelected() && portAsInteger != null && portAsInteger == Protocol.TCP.getDefaultPort()) {
                        useSslOriginal = true;
                        electrumUseSsl.setSelected(false);
                    }

                    if(useSslOriginal != null) {
                        EventManager.get().register(connectionService);
                        connectionService.reset();
                        connectionService.start();
                    }
                } else {
                    electrumUseSsl.setSelected(useSslOriginal);
                    useSslOriginal = null;
                }
            }

            if(useProxyOriginal != null && !useProxyOriginal) {
                useProxy.setSelected(false);
                proxyHost.setText("");
                proxyPort.setText("");
                useProxyOriginal = null;
            }
        });
        connectionService.start();
    }

    private void setFieldsEditable(boolean editable) {
        serverTypeToggleGroup.getToggles().forEach(toggle -> ((ToggleButton)toggle).setDisable(!editable));

        publicElectrumServer.setDisable(!editable);
        publicUseProxy.setDisable(!editable);
        publicProxyHost.setDisable(!editable);
        publicProxyPort.setDisable(!editable);

        if(coreHost != null) coreHost.setDisable(!editable);
        if(corePort != null) corePort.setDisable(!editable);
        if(coreAuthToggleGroup != null) coreAuthToggleGroup.getToggles().forEach(toggle -> ((ToggleButton)toggle).setDisable(!editable));
        if(coreDataDir != null) coreDataDir.setDisable(!editable);
        if(coreDataDirSelect != null) coreDataDirSelect.setDisable(!editable);
        if(coreUser != null) coreUser.setDisable(!editable);
        if(corePass != null) corePass.setDisable(!editable);
        if(coreUseProxy != null) coreUseProxy.setDisable(!editable);
        if(coreProxyHost != null) coreProxyHost.setDisable(!editable);
        if(coreProxyPort != null) coreProxyPort.setDisable(!editable);

        electrumHost.setDisable(!editable);
        electrumPort.setDisable(!editable);
        electrumUseSsl.setDisable(!editable);
        electrumCertificate.setDisable(!editable);
        electrumCertificateSelect.setDisable(!editable);
        useProxy.setDisable(!editable);
        proxyHost.setDisable(!editable);
        proxyPort.setDisable(!editable);
    }

    private void showConnectionSuccess(List<String> serverVersion, String serverBanner) {
        testConnection.setGraphic(getGlyph(FontAwesome5.Glyph.CHECK_CIRCLE, "success"));
        if(serverVersion != null) {
            testResults.setText("Connected to " + serverVersion.get(0) + " on protocol version " + serverVersion.get(1));
            if(ElectrumServer.supportsBatching(serverVersion)) {
                testResults.setText(testResults.getText() + "\nBatched RPC enabled.");
            }
        }
        if(serverBanner != null) {
            testResults.setText(testResults.getText() + "\nServer Banner: " + serverBanner);
        }
        testResults.appendText("\n\nSettings saved. Establishing connection in the background...");
    }

    private void showConnectionFailure(Throwable exception) {
        log.error("Connection error", exception);

        String reason;
        if(Throwables.getRootCause(exception) instanceof JsonRpcException jsonRpcException && jsonRpcException.getErrorMessage() != null) {
            reason = jsonRpcException.getErrorMessage().getMessage() + (jsonRpcException.getErrorMessage().getData() != null ? " (" + jsonRpcException.getErrorMessage().getData().asText() + ")" : "");
        } else {
            reason = exception.getCause() != null ? exception.getCause().getMessage() : exception.getMessage();
        }

        if(exception instanceof TlsServerException && exception.getCause() != null) {
            TlsServerException tlsServerException = (TlsServerException)exception;
            if(exception.getCause().getMessage().contains("PKIX path building failed")) {
                File configCrtFile = Config.get().getElectrumServerCert();
                File savedCrtFile = Storage.getCertificateFile(tlsServerException.getServer().getHost());
                if(configCrtFile == null && savedCrtFile != null) {
                    Optional<ButtonType> optButton = AppServices.showErrorDialog("SSL Handshake Failed", "The certificate provided by the server at " + tlsServerException.getServer().getHost() + " appears to have changed." +
                            "\n\nThis may indicate a man-in-the-middle attack!" +
                            "\n\nDo you still want to proceed?", ButtonType.NO, ButtonType.YES);
                    if(optButton.isPresent() && optButton.get() == ButtonType.YES) {
                        if(savedCrtFile.delete()) {
                            Platform.runLater(this::startElectrumConnection);
                            return;
                        } else {
                            AppServices.showErrorDialog("Could not delete certificate", "The certificate file at " + savedCrtFile.getAbsolutePath() + " could not be deleted.\n\nPlease delete this file manually.");
                        }
                    }
                }
            }

            reason = tlsServerException.getMessage() + "\n\n" + reason;
        } else if(exception instanceof ProxyServerException) {
            reason += ". Check if the proxy server is running.";
        } else if(reason != null && (reason.contains("Check if Bitcoin Core is running") || reason.contains("Could not connect to Bitcoin Core RPC"))) {
            reason += "\n\nSee https://github.com/linkinparkrulz/ashigaru-desktop#readme";
        } else if(reason != null && (reason.startsWith("Cannot connect to hidden service"))) {
            reason += " on the server. Check that the onion address and port are correct, and that both Tor and the Electrum server are running on the node. Usually SSL is not enabled, and the port is 50001.";
        } else if(reason != null && (reason.startsWith("Cannot find Bitcoin Core cookie file at"))) {
            reason += "\n\nMake sure server=1 has been added to bitcoin.conf";
        }

        testResults.setText("Could not connect:\n\n" + reason);
        testConnection.setGraphic(getGlyph(FontAwesome5.Glyph.EXCLAMATION_CIRCLE, "failure"));
    }

    private void setupValidation() {
        validationSupport.setValidationDecorator(new StyleClassValidationDecoration());

        validationSupport.registerValidator(publicProxyHost, Validator.combine(
                (Control c, String newValue) -> ValidationResult.fromErrorIf( c, "Proxy host required", publicUseProxy.isSelected() && newValue.isEmpty()),
                (Control c, String newValue) -> ValidationResult.fromErrorIf( c, "Invalid host name", getHost(newValue) == null)
        ));

        validationSupport.registerValidator(publicProxyPort, Validator.combine(
                (Control c, String newValue) -> ValidationResult.fromErrorIf( c, "Invalid proxy port", !newValue.isEmpty() && !isValidPort(Integer.parseInt(newValue)))
        ));

        if(coreHost != null) validationSupport.registerValidator(coreHost, Validator.combine(
                (Control c, String newValue) -> ValidationResult.fromErrorIf( c, "Invalid Core host", getHost(newValue) == null)
        ));

        if(corePort != null) validationSupport.registerValidator(corePort, Validator.combine(
                (Control c, String newValue) -> ValidationResult.fromErrorIf( c, "Invalid Core port", !newValue.isEmpty() && !isValidPort(Integer.parseInt(newValue)))
        ));

        if(coreDataDir != null && coreAuthToggleGroup != null) validationSupport.registerValidator(coreDataDir, Validator.combine(
                (Control c, String newValue) -> ValidationResult.fromErrorIf( c, "Core Data Dir required", coreAuthToggleGroup.getSelectedToggle().getUserData() == CoreAuthType.COOKIE && (newValue.isEmpty() || getDirectory(newValue) == null))
        ));

        if(coreUser != null && coreAuthToggleGroup != null) validationSupport.registerValidator(coreUser, Validator.combine(
                (Control c, String newValue) -> ValidationResult.fromErrorIf( c, "Core user required", coreAuthToggleGroup.getSelectedToggle().getUserData() == CoreAuthType.USERPASS && newValue.isEmpty())
        ));

        if(corePass != null && coreAuthToggleGroup != null) validationSupport.registerValidator(corePass, Validator.combine(
                (Control c, String newValue) -> ValidationResult.fromErrorIf( c, "Core pass required", coreAuthToggleGroup.getSelectedToggle().getUserData() == CoreAuthType.USERPASS && newValue.isEmpty())
        ));

        validationSupport.registerValidator(electrumHost, Validator.combine(
                (Control c, String newValue) -> ValidationResult.fromErrorIf( c, "Invalid Electrum host", getHost(newValue) == null)
        ));

        validationSupport.registerValidator(electrumPort, Validator.combine(
                (Control c, String newValue) -> ValidationResult.fromErrorIf( c, "Invalid Electrum port", !newValue.isEmpty() && !isValidPort(Integer.parseInt(newValue)))
        ));

        validationSupport.registerValidator(proxyHost, Validator.combine(
                (Control c, String newValue) -> ValidationResult.fromErrorIf( c, "Proxy host required", useProxy.isSelected() && newValue.isEmpty()),
                (Control c, String newValue) -> ValidationResult.fromErrorIf( c, "Invalid host name", getHost(newValue) == null)
        ));

        validationSupport.registerValidator(proxyPort, Validator.combine(
                (Control c, String newValue) -> ValidationResult.fromErrorIf( c, "Invalid proxy port", !newValue.isEmpty() && !isValidPort(Integer.parseInt(newValue)))
        ));

        validationSupport.registerValidator(electrumCertificate, Validator.combine(
                (Control c, String newValue) -> ValidationResult.fromErrorIf( c, "Invalid certificate file", newValue != null && !newValue.isEmpty() && getCertificate(newValue) == null)
        ));
    }

    @NotNull
    private ChangeListener<Server> getPublicElectrumServerListener(Config config) {
        return (observable, oldValue, newValue) -> {
            if(newValue != null) {
                config.setPublicElectrumServer(newValue);
            }
        };
    }

    @NotNull
    private ChangeListener<String> getBitcoinCoreListener(Config config) {
        return (observable, oldValue, newValue) -> {
            Server existingServer = config.getRecentCoreServers().stream().filter(server -> coreHost.getText().equals(server.getAlias())).findFirst().orElse(null);
            if(existingServer != null && !existingServer.portEquals(corePort.getText())) {
                coreHost.setText(existingServer.getHost());
                existingServer = null;
            }
            coreHost.setLeft(existingServer == null ? null : getGlyph(FontAwesome5.Glyph.TAG, null));
            setCoreServerInConfig(config);
        };
    }

    private void setCoreServerInConfig(Config config) {
        Server existingServer = config.getRecentCoreServers().stream().filter(server -> coreHost.getText().equals(server.getAlias())).findFirst().orElse(null);
        if(existingServer != null) {
            config.setCoreServer(existingServer);
            return;
        }

        String hostAsString = getHost(coreHost.getText());
        Integer portAsInteger = getPort(corePort.getText());
        if(hostAsString != null && !hostAsString.isEmpty() && portAsInteger != null && isValidPort(portAsInteger)) {
            Protocol protocol = portAsInteger == Protocol.HTTPS.getDefaultPort() ? Protocol.HTTPS : Protocol.HTTP;
            config.setCoreServer(new Server(protocol.toUrlString(hostAsString, portAsInteger)));
        } else if(hostAsString != null && !hostAsString.isEmpty()) {
            config.setCoreServer(new Server(Protocol.HTTP.toUrlString(hostAsString)));
        } else {
            config.setCoreServer(null);
        }
    }

    @NotNull
    private ChangeListener<String> getBitcoinAuthListener(Config config) {
        return (observable, oldValue, newValue) -> {
            config.setCoreAuth(coreUser.getText() + ":" + corePass.getText());
        };
    }

    @NotNull
    private ChangeListener<String> getElectrumServerListener(Config config) {
        return (observable, oldValue, newValue) -> {
            Server existingServer = config.getRecentElectrumServers().stream().filter(server -> electrumHost.getText().equals(server.getAlias())).findFirst().orElse(null);
            if(existingServer != null && !existingServer.portEquals(electrumPort.getText())) {
                electrumHost.setText(existingServer.getHost());
                existingServer = null;
            }
            electrumHost.setLeft(existingServer == null ? null : getGlyph(FontAwesome5.Glyph.TAG, null));
            setElectrumServerInConfig(config);
        };
    }

    private void setElectrumServerInConfig(Config config) {
        Server existingServer = config.getRecentElectrumServers().stream().filter(server -> electrumHost.getText().equals(server.getAlias())).findFirst().orElse(null);
        if(existingServer != null) {
            config.setElectrumServer(existingServer);
            return;
        }

        String hostAsString = getHost(electrumHost.getText());
        Integer portAsInteger = getPort(electrumPort.getText());
        if(hostAsString != null && !hostAsString.isEmpty() && portAsInteger != null && isValidPort(portAsInteger)) {
            config.setElectrumServer(new Server(getProtocol().toUrlString(hostAsString, portAsInteger)));
        } else if(hostAsString != null && !hostAsString.isEmpty()) {
            config.setElectrumServer(new Server(getProtocol().toUrlString(hostAsString)));
        } else {
            config.setElectrumServer(null);
        }
    }

    @NotNull
    private ChangeListener<String> getProxyListener(Config config) {
        return (observable, oldValue, newValue) -> {
            if(oldValue.trim().equals(newValue.trim())) {
                return;
            }

            String hostAsString = getHost(proxyHost.getText());
            Integer portAsInteger = getPort(proxyPort.getText());
            if(hostAsString != null && portAsInteger != null && isValidPort(portAsInteger)) {
                config.setProxyServer(HostAndPort.fromParts(hostAsString, portAsInteger).toString());
            } else if(hostAsString != null) {
                config.setProxyServer(HostAndPort.fromHost(hostAsString).toString());
            }
        };
    }

    private Protocol getProtocol() {
        return (electrumUseSsl.isSelected() ? Protocol.SSL : Protocol.TCP);
    }

    private String getHost(String text) {
        try {
            return HostAndPort.fromHost(text).getHost();
        } catch(IllegalArgumentException e) {
            return null;
        }
    }

    private Integer getPort(String text) {
        try {
            return Integer.parseInt(text);
        } catch(NumberFormatException e) {
            return null;
        }
    }

    private File getDirectory(String dirLocation) {
        try {
            File dirFile = new File(dirLocation);
            if(!dirFile.exists() || !dirFile.isDirectory()) {
                return null;
            }

            return dirFile;
        } catch (Exception e) {
            return null;
        }
    }

    private File getCertificate(String crtFileLocation) {
        try {
            File crtFile = new File(crtFileLocation);
            if(!crtFile.exists()) {
                return null;
            }

            CertificateFactory.getInstance("X.509").generateCertificate(new FileInputStream(crtFile));
            return crtFile;
        } catch (Exception e) {
            return null;
        }
    }

    private static Glyph getGlyph(FontAwesome5.Glyph glyphName, String styleClass) {
        Glyph glyph = new Glyph(FontAwesome5.FONT_NAME, glyphName);
        glyph.setFontSize(12);
        if(styleClass != null) {
            glyph.getStyleClass().add(styleClass);
        }

        return glyph;
    }

    private static boolean isValidPort(int port) {
        return port >= 0 && port <= 65535;
    }

    private File getDefaultCoreDataDir() {
        org.controlsfx.tools.Platform platform = org.controlsfx.tools.Platform.getCurrent();
        if(platform == org.controlsfx.tools.Platform.OSX) {
            return new File(System.getProperty("user.home") + "/Library/Application Support/Bitcoin");
        } else if(platform == org.controlsfx.tools.Platform.WINDOWS) {
            return new File(System.getenv("APPDATA") + "/Bitcoin");
        } else {
            return new File(System.getProperty("user.home") + "/.bitcoin");
        }
    }

    private void setTestResultsFont() {
        org.controlsfx.tools.Platform platform = org.controlsfx.tools.Platform.getCurrent();
        if(platform == org.controlsfx.tools.Platform.OSX) {
            testResults.setFont(Font.font("Monaco", 11));
        } else if(platform == org.controlsfx.tools.Platform.WINDOWS) {
            testResults.setFont(Font.font("Lucida Console", 11));
        } else {
            testResults.setFont(Font.font("monospace", 11));
        }
    }

    private ObservableList<Server> getObservableServerList(List<Server> servers) {
        ObservableList<Server> serverObservableList = FXCollections.observableList(new ArrayList<>(servers));
        serverObservableList.add(MANAGE_ALIASES_SERVER);
        return serverObservableList;
    }

    private ObservableList<Server> getPublicServerList() {
        List<Server> servers = PublicElectrumServer.getServers().stream().map(PublicElectrumServer::getServer).collect(Collectors.toList());
        for(Server discovered : Config.get().getDiscoveredServers()) {
            if(!servers.contains(discovered)) {
                servers.add(discovered);
            }
        }
        return FXCollections.observableList(servers);
    }

    @FXML
    private void discoverNodes(ActionEvent event) {
        discoverNodesLink.setDisable(true);
        discoverNodesStatus.setText("Discovering Dojo Electrum servers over Tor…");

        Task<DojoNodeDiscovery.DiscoveryResult> task = new Task<>() {
            @Override
            protected DojoNodeDiscovery.DiscoveryResult call() throws Exception {
                return DojoNodeDiscovery.discover(AppServices.getHttpClientService());
            }
        };

        task.setOnSucceeded(e -> {
            DojoNodeDiscovery.DiscoveryResult result = task.getValue();
            int verified = 0;
            int unverified = result.getUnverified();
            for(DojoNodeDiscovery.DiscoveredNode node : result.getNodes()) {
                if(node.isVerified()) {
                    Config.get().addDiscoveredServer(node.getIndexerServer());
                    if(node.getExplorerServer() != null) {
                        Config.get().addDiscoveredExplorer(node.getExplorerServer());
                    }
                    verified++;
                }
            }

            refreshPublicServerList();

            if(verified == 0 && unverified == 0) {
                discoverNodesStatus.setText("No Dojos found for this network.");
            } else {
                discoverNodesStatus.setText("Added " + verified + " Dojo Electrum server" + (verified == 1 ? "" : "s")
                        + "; " + unverified + " unverified. Directory courtesy of dojobay.pw");
            }
            discoverNodesLink.setDisable(false);
        });

        task.setOnFailed(e -> {
            Throwable ex = task.getException();
            discoverNodesStatus.setText("Discovery failed: " + (ex != null ? ex.getMessage() : "unknown error"));
            discoverNodesLink.setDisable(false);
        });

        Thread thread = new Thread(task, "dojo-node-discovery");
        thread.setDaemon(true);
        thread.start();
    }

    private void refreshPublicServerList() {
        Server current = publicElectrumServer.getValue();
        publicElectrumServer.setItems(getPublicServerList());
        if(current != null) {
            publicElectrumServer.setValue(current);
        }
    }

    @Subscribe
    public void discoveredServersChanged(DiscoveredServersChangedEvent event) {
        Platform.runLater(() -> {
            if(publicElectrumServer != null) {
                refreshPublicServerList();
            }
        });
    }

    @Subscribe
    public void cormorantSyncStatus(CormorantSyncStatusEvent event) {
        editConnection.setDisable(false);
        if(connectionService != null && connectionService.isRunning() && event.getProgress() < 100) {
            DateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm");
            testResults.appendText("\nThe connection to the Bitcoin Core node was successful, but it is still syncing and cannot be used yet.");
            testResults.appendText("\nCurrently " + event.getProgress() + "% completed to date " + dateFormat.format(event.getTip()));
            testConnection.setGraphic(getGlyph(FontAwesome5.Glyph.QUESTION_CIRCLE, null));
            connectionService.cancel();
        }
    }

    @Subscribe
    public void bwtStatus(BwtStatusEvent event) {
        if(!(event instanceof BwtSyncStatusEvent)) {
            testResults.appendText("\n" + event.getStatus());
        }
        if(event instanceof BwtReadyStatusEvent) {
            editConnection.setDisable(false);
        }
    }

    @Subscribe
    public void bwtSyncStatus(BwtSyncStatusEvent event) {
        editConnection.setDisable(false);
        if(connectionService != null && connectionService.isRunning() && event.getProgress() < 100) {
            DateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd HH:mm");
            testResults.appendText("\nThe connection to the Bitcoin Core node was successful, but it is still syncing and cannot be used yet.");
            testResults.appendText("\nCurrently " + event.getProgress() + "% completed to date " + dateFormat.format(event.getTip()));
            testConnection.setGraphic(getGlyph(FontAwesome5.Glyph.QUESTION_CIRCLE, null));
            connectionService.cancel();
        }
    }

    @Subscribe
    public void torStatus(TorStatusEvent event) {
        Platform.runLater(() -> {
            if((torService != null && torService.isRunning()) || restartingTor) {
                testResults.appendText("\n" + event.getStatus());
            }
            if(restartingTor && event instanceof TorReadyStatusEvent) {
                restartingTor = false;
                testResults.appendText("\nTor restarted, reconnecting...");
            } else if(restartingTor && event instanceof TorFailedStatusEvent) {
                restartingTor = false;
                testResults.appendText("\nTor failed to restart");
            }
        });
    }

    private static class ServerCell extends ListCell<Server> {
        @Override
        protected void updateItem(Server server, boolean empty) {
            super.updateItem(server, empty);
            if(server == null || empty) {
                setText("");
                setGraphic(null);
            } else {
                String serverAlias = server.getAlias();

                if(server == MANAGE_ALIASES_SERVER) {
                    setText(serverAlias);
                    setStyle("-fx-font-style: italic");
                    setGraphic(null);
                } else if(serverAlias != null) {
                    setText(serverAlias);
                    setGraphic(getGlyph(FontAwesome5.Glyph.TAG, null));
                } else {
                    setText(server.getHost());
                    setGraphic(null);
                }
            }
        }
    }
}
