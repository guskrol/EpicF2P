package org.example.core.runtime;

import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicReference;

public final class BreakSettingsDialog {
    private BreakSettingsDialog() { }

    /** null means startup was cancelled. */
    public static Boolean choose() {
        AtomicReference<Boolean> result = new AtomicReference<>();
        Runnable show = () -> {
            JCheckBox enabled = new JCheckBox("Ativar sistema de breaks", false);
            JPanel antiban = new JPanel(new BorderLayout(0, 12));
            antiban.add(enabled, BorderLayout.NORTH);
            antiban.add(new JLabel("Micro, normal, bio, refeições e sleeping"), BorderLayout.CENTER);
            JTabbedPane tabs = new JTabbedPane();
            tabs.addTab("Antiban", antiban);
            int choice = JOptionPane.showConfirmDialog(null, tabs, "F2P Goals - Configuração",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            result.set(choice == JOptionPane.OK_OPTION ? enabled.isSelected() : null);
        };
        if (SwingUtilities.isEventDispatchThread()) show.run();
        else {
            try { SwingUtilities.invokeAndWait(show); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return null; }
            catch (InvocationTargetException e) { throw new IllegalStateException("Break settings failed", e); }
        }
        return result.get();
    }
}

