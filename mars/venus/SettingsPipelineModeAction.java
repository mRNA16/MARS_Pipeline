package mars.venus;

import mars.*;
import mars.simulator.*;
import java.awt.event.*;
import javax.swing.*;

/**
 * Action class for the Settings menu item to control pipeline mode.
 */
public class SettingsPipelineModeAction extends GuiAction {

    public SettingsPipelineModeAction(String name, Icon icon, String descrip,
            Integer mnemonic, KeyStroke accel, VenusUI gui) {
        super(name, icon, descrip, mnemonic, accel, gui);
    }

    public void actionPerformed(ActionEvent e) {
        boolean enabled = ((JCheckBoxMenuItem) e.getSource()).isSelected();
        Globals.getSettings().setBooleanSetting(Settings.PIPELINE_MODE, enabled);

        // Update ExecutePane layout
        if (Globals.getGui() != null) {
            Globals.getGui().getMainPane().getExecutePane().setWindowBounds();
            // Re-assemble if needed, as pipeline mode might affect how we simulate
            if (FileStatus.get() == FileStatus.RUNNABLE ||
                    FileStatus.get() == FileStatus.RUNNING ||
                    FileStatus.get() == FileStatus.TERMINATED) {
                if (FileStatus.get() == FileStatus.RUNNING) {
                    Simulator.getInstance().stopExecution(this);
                }
                Globals.getGui().getRunAssembleAction().actionPerformed(null);
            }
        }
    }
}
