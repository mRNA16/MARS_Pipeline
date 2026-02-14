package mars.venus;

import java.awt.event.*;
import javax.swing.*;
import java.awt.*;

/**
 * Action to print the current layout bounds of all relevant UI components.
 * This is used for debugging and adjusting the initial layout.
 */
public class SettingsPrintLayoutAction extends GuiAction {

    public SettingsPrintLayoutAction(String name, Icon icon, String descrip,
            Integer mnemonic, KeyStroke accel, VenusUI gui) {
        super(name, icon, descrip, mnemonic, accel, gui);
    }

    public void actionPerformed(ActionEvent e) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n========== MARS UI LAYOUT SNAPSHOT ==========\n");
        sb.append("Current Window Size: ").append(mainUI.getSize().width).append("x").append(mainUI.getSize().height)
                .append("\n\n");

        // 1. Splitter positions
        if (mainUI.splitter != null) {
            sb.append("Main Splitter (Left/Right) Divider Location: ").append(mainUI.splitter.getDividerLocation())
                    .append("\n");
        }
        if (mainUI.horizonSplitter != null) {
            sb.append("Horizon Splitter (Top/Bottom) Divider Location: ")
                    .append(mainUI.horizonSplitter.getDividerLocation()).append("\n");
        }
        sb.append("\n");

        // 2. Main Panes
        sb.append("Registers Pane Bounds: ").append(formatBounds(mainUI.registersPane.getBounds())).append("\n");
        sb.append("Messages Pane Bounds: ").append(formatBounds(mainUI.messagesPane.getBounds())).append("\n");
        sb.append("Main Pane (Editor/Execute) Bounds: ").append(formatBounds(mainUI.mainPane.getBounds())).append("\n");
        sb.append("\n");

        // 3. ExecutePane Internal Frames (if in execute mode)
        ExecutePane executePane = mainUI.mainPane.getExecutePane();
        if (executePane != null) {
            sb.append("--- ExecutePane Internal Windows ---\n");
            sb.append("Pipeline View: ").append(formatBounds(executePane.getPipelineWindow().getBounds())).append("\n");
            sb.append("Clock Cycle Diagram: ").append(formatBounds(executePane.getClockCycleWindow().getBounds()))
                    .append("\n");
            sb.append("Pipeline Snapshot: ").append(formatBounds(executePane.getPipelineSnapshotWindow().getBounds()))
                    .append("\n");
            sb.append("Text Segment: ").append(formatBounds(executePane.getTextSegmentWindow().getBounds()))
                    .append("\n");
            sb.append("Data Segment: ").append(formatBounds(executePane.getDataSegmentWindow().getBounds()))
                    .append("\n");
            sb.append("Labels Window: ").append(formatBounds(executePane.getLabelsWindow().getBounds())).append("\n");
        }

        sb.append("=============================================\n");
        System.out.println(sb.toString());

        JOptionPane.showMessageDialog(mainUI, "布局信息已打印到控制台 (Console)。");
    }

    private String formatBounds(Rectangle r) {
        if (r == null)
            return "null";
        return "x=" + r.x + ", y=" + r.y + ", w=" + r.width + ", h=" + r.height;
    }
}
