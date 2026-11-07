package com.sparrowwallet.sparrow.preferences;

import com.sparrowwallet.drongo.Network;
import com.sparrowwallet.sparrow.testutil.JavaFXTestSupport;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.URL;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FXML mistakes (a bad fx:id, an unknown glyph, a missing handler) only show when the file is loaded, so load
 * the Network page and the Settings sidebar that links to it.
 */
class NetworkPreferencesFxmlTest {
    @BeforeAll
    static void startToolkit() {
        JavaFXTestSupport.ensureStarted();
    }

    private static <T> T field(Object target, String name, Class<T> type) throws Exception {
        Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return type.cast(f.get(target));
    }

    @Test
    void networkPageLoadsAndShowsTheActiveNetwork() throws Exception {
        URL fxml = NetworkPreferencesController.class.getResource("/com/sparrowwallet/sparrow/preferences/network.fxml");
        assertNotNull(fxml, "network.fxml must be on the classpath next to the other preference pages");

        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<NetworkPreferencesController> controllerRef = new AtomicReference<>();
        JavaFXTestSupport.runAndWait(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(fxml);
                Node root = loader.load();
                assertNotNull(root);

                NetworkPreferencesController controller = loader.getController();
                controller.initializeView(null);
                controllerRef.set(controller);
            } catch(Throwable t) {
                failure.set(t);
            }
        });
        if(failure.get() != null) {
            throw new AssertionError("network.fxml failed to load", failure.get());
        }

        NetworkPreferencesController controller = controllerRef.get();
        assertEquals(Network.get().toDisplayString(), field(controller, "activeNetworkLabel", Label.class).getText());

        @SuppressWarnings("unchecked")
        ComboBox<Network> combo = field(controller, "networkCombo", ComboBox.class);
        assertEquals(java.util.List.of(Network.MAINNET, Network.TESTNET4), combo.getItems());
        assertTrue(field(controller, "switchBtn", Button.class).isDisable(), "nothing to switch to until another network is picked");
    }

    @Test
    void invalidServerIsRejectedWithoutBeingSaved() throws Exception {
        URL fxml = NetworkPreferencesController.class.getResource("/com/sparrowwallet/sparrow/preferences/network.fxml");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<NetworkPreferencesController> controllerRef = new AtomicReference<>();
        JavaFXTestSupport.runAndWait(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(fxml);
                loader.load();
                NetworkPreferencesController controller = loader.getController();
                controller.initializeView(null);

                //Plain http to a public host is refused
                field(controller, "testnetCoordinator", TextField.class).setText("http://pool.example.com");
                field(controller, "testnetSave", Button.class).fire();
                controllerRef.set(controller);
            } catch(Throwable t) {
                failure.set(t);
            }
        });
        if(failure.get() != null) {
            throw new AssertionError("could not exercise the page", failure.get());
        }

        Label status = field(controllerRef.get(), "testnetStatus", Label.class);
        assertTrue(status.getText().contains("https://"), "the problem should be shown, got: " + status.getText());
        assertTrue(status.getStyleClass().contains("verifier-step-fail"), "and styled as an error");
    }

    @Test
    void settingsSidebarLinksToTheNetworkPage() throws Exception {
        URL fxml = PreferencesController.class.getResource("/com/sparrowwallet/sparrow/preferences/preferences.fxml");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<PreferencesController> controllerRef = new AtomicReference<>();
        JavaFXTestSupport.runAndWait(() -> {
            try {
                FXMLLoader loader = new FXMLLoader(fxml);
                loader.load();
                controllerRef.set(loader.getController());
            } catch(Throwable t) {
                failure.set(t);
            }
        });
        if(failure.get() != null) {
            throw new AssertionError("preferences.fxml failed to load", failure.get());
        }

        javafx.scene.control.ToggleGroup group = field(controllerRef.get(), "preferencesMenu", javafx.scene.control.ToggleGroup.class);
        assertTrue(group.getToggles().stream().anyMatch(t -> t.getUserData() == PreferenceGroup.NETWORK),
                "the sidebar should have a Network entry");
        //The page is loaded by the lower-cased group name, so the file has to match it
        assertNotNull(PreferencesController.class.getResource("/com/sparrowwallet/sparrow/preferences/" + PreferenceGroup.NETWORK.toString().toLowerCase(java.util.Locale.ROOT) + ".fxml"));
    }
}
