package mars.venus;

import mars.*;
import mars.mips.hardware.RegisterFile;
import mars.simulator.*;
import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.concurrent.locks.*;

/**
 * Window to display the Pipeline Snapshot (Time-Stage Diagram).
 * X-axis: Pipeline Stages (IF, ID, EX, MEM, WB).
 * Y-axis: Clock Cycles (0, 1, 2, ...).
 * Cell Content: The instruction currently in that stage.
 */
public class PipelineSnapshotWindow extends JInternalFrame implements Observer {
    private DiagramPanel diagramPanel;
    private JScrollPane scrollPane;

    private static final int ROW_HEIGHT = 22;
    private static final int COL_WIDTH = 160; // Wider for better readability
    private static final int LABEL_WIDTH = 40;
    private static final int HEADER_HEIGHT = 25;

    private long lastUpdateTime = 0;
    private static final long UPDATE_THROTTLE_MS = 100;
    private volatile boolean updatePending = false;

    public PipelineSnapshotWindow() {
        super("流水线快照", true, false, true, true);
        diagramPanel = new DiagramPanel();
        scrollPane = new JScrollPane(diagramPanel);

        // Header for cycles
        JPanel corner = new JPanel() {
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                g.setColor(new Color(240, 240, 240));
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(Color.GRAY);
                g.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
                g.setColor(Color.BLACK);
                g.setFont(new Font("SansSerif", Font.PLAIN, 11));
                g.drawString(" 周期 / 级", 10, 17);
            }
        };
        corner.setPreferredSize(new Dimension(LABEL_WIDTH, HEADER_HEIGHT));
        scrollPane.setCorner(JScrollPane.UPPER_LEFT_CORNER, corner);

        this.getContentPane().add(scrollPane);
        PipelineSimulator.getInstance().addObserver(this);
        this.setSize(750, 400);
    }

    public void update(Observable observable, Object obj) {
        if (updatePending)
            return;
        long now = System.currentTimeMillis();
        boolean isReset = "RESET".equals(obj);

        if (!isReset && obj == null && now - lastUpdateTime < UPDATE_THROTTLE_MS)
            return;

        if (isReset) {
            updatePending = false;
        }

        updatePending = true;
        lastUpdateTime = now;

        SwingUtilities.invokeLater(() -> {
            try {
                diagramPanel.recalculateSize();
                diagramPanel.repaint();

                // Auto-scroll to the bottom
                JScrollBar vertical = scrollPane.getVerticalScrollBar();
                if (!vertical.getValueIsAdjusting()) {
                    vertical.setValue(vertical.getMaximum());
                }
            } finally {
                updatePending = false;
            }
        });
    }

    private class DiagramPanel extends JPanel implements Scrollable {
        private final String[] STAGES = { "IF", "ID", "EX", "MEM", "WB" };
        private final Color[] STAGE_COLORS = {
                new Color(255, 255, 200), // IF
                new Color(255, 230, 200), // ID
                new Color(255, 210, 210), // EX
                new Color(210, 255, 255), // MEM
                new Color(210, 255, 210) // WB
        };

        public DiagramPanel() {
            setOpaque(true);
            setBackground(Color.WHITE);
        }

        public void recalculateSize() {
            int totalCycles = PipelineSimulator.getInstance().getCycles();
            Dimension d = new Dimension(
                    LABEL_WIDTH + STAGES.length * COL_WIDTH,
                    HEADER_HEIGHT + (totalCycles + 5) * ROW_HEIGHT);
            if (!getPreferredSize().equals(d)) {
                setPreferredSize(d);
                revalidate();
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            Rectangle clip = g.getClipBounds();
            if (clip == null)
                return;

            PipelineSimulator sim = PipelineSimulator.getInstance();
            ReentrantReadWriteLock lock = sim.getLock();

            lock.readLock().lock();
            try {
                int totalCycles = sim.getCycles();
                java.util.List<PipelineSimulator.ExecutionStep> history = sim.getExecutionHistory();

                // Draw Fixed Column Header (Stages)
                drawStageHeaders(g2, clip);

                // Draw Cycle Labels (Left Column)
                drawCycleLabels(g2, clip, totalCycles);

                // Draw Instructions in Stages
                drawPipelineGrid(g2, clip, history, totalCycles, sim);

            } finally {
                lock.readLock().unlock();
            }
        }

        private void drawStageHeaders(Graphics2D g, Rectangle clip) {
            g.setColor(new Color(240, 240, 240));
            // Header is at y = 0 relative to the component, but we need it sticky?
            // Wait, JScrollPane headers are handled by setColumnHeaderView.
            // But if I want to keep it simple within one panel for now:
            int headerY = Math.max(0, clip.y); // Simplified sticky
            // Actually, let's use the scrollPane header mechanism if possible.
            // For now, I'll just draw it.

            g.fillRect(clip.x, headerY, clip.width, HEADER_HEIGHT);
            g.setColor(Color.GRAY);
            g.drawLine(clip.x, headerY + HEADER_HEIGHT - 1, clip.x + clip.width, headerY + HEADER_HEIGHT - 1);

            g.setColor(Color.BLACK);
            g.setFont(new Font("SansSerif", Font.BOLD, 12));
            for (int i = 0; i < STAGES.length; i++) {
                int x = LABEL_WIDTH + i * COL_WIDTH;
                g.drawString(STAGES[i], x + COL_WIDTH / 2 - 10, headerY + 17);
                g.drawLine(x, headerY, x, headerY + HEADER_HEIGHT);
            }
        }

        private void drawCycleLabels(Graphics2D g, Rectangle clip, int totalCycles) {
            g.setColor(new Color(240, 240, 240));
            g.fillRect(0, clip.y, LABEL_WIDTH, clip.height);
            g.setColor(Color.GRAY);
            g.drawLine(LABEL_WIDTH - 1, clip.y, LABEL_WIDTH - 1, clip.y + clip.height);

            g.setColor(Color.BLACK);
            g.setFont(new Font("SansSerif", Font.PLAIN, 12));
            int startCycle = Math.max(0, (clip.y - HEADER_HEIGHT) / ROW_HEIGHT);
            int endCycle = (clip.y + clip.height - HEADER_HEIGHT) / ROW_HEIGHT + 1;

            for (int c = startCycle; c <= Math.min(endCycle, totalCycles); c++) {
                int y = HEADER_HEIGHT + c * ROW_HEIGHT;
                g.drawString("C " + c, 10, y + 16);
                g.setColor(new Color(230, 230, 230));
                g.drawLine(0, y + ROW_HEIGHT, LABEL_WIDTH, y + ROW_HEIGHT);
                g.setColor(Color.BLACK);
            }
        }

        private void drawPipelineGrid(Graphics2D g, Rectangle clip,
                java.util.List<PipelineSimulator.ExecutionStep> history,
                int totalCycles, PipelineSimulator sim) {
            int startCycle = Math.max(0, (clip.y - HEADER_HEIGHT) / ROW_HEIGHT);
            int endCycle = (clip.y + clip.height - HEADER_HEIGHT) / ROW_HEIGHT + 1;

            for (int c = startCycle; c <= Math.min(endCycle, totalCycles); c++) {
                int y = HEADER_HEIGHT + c * ROW_HEIGHT;
                int xOffset = LABEL_WIDTH;

                // For each stage in this cycle
                for (int sIdx = 0; sIdx < STAGES.length; sIdx++) {
                    String stageName = STAGES[sIdx];
                    String instrStr = findInstructionAt(c, stageName, history, sim);

                    if (instrStr == null && c < totalCycles) {
                        instrStr = "nop"; // Bubble
                    }

                    if (instrStr != null) {
                        g.setColor(instrStr.equals("nop") ? new Color(250, 218, 139) : STAGE_COLORS[sIdx]);
                        g.fillRect(xOffset + 1, y + 1, COL_WIDTH - 2, ROW_HEIGHT - 2);

                        // Highlight stall boxes in current cycle
                        if (c == totalCycles) {
                            PipelineSimulator.HazardInfo hazard = sim.getHazardInfo();
                            if (hazard != null && hazard.stalled) {
                                int idIdx = 1;
                                int causeIdx = hazard.stallSource.equals("EX") ? 2 : 3;
                                if (sIdx == idIdx || sIdx == causeIdx) {
                                    g.setColor(Color.RED);
                                    g.setStroke(new BasicStroke(2));
                                    g.drawRect(xOffset + 1, y + 1, COL_WIDTH - 2, ROW_HEIGHT - 2);
                                }
                            }
                        }

                        g.setColor(Color.BLACK);
                        g.setFont(new Font("Monospaced", Font.PLAIN, 11));
                        // Truncate if too long (now with more space)
                        String displayStr = instrStr;
                        if (displayStr.length() > 25)
                            displayStr = displayStr.substring(0, 22) + "...";
                        g.drawString(displayStr, xOffset + 5, y + 15);
                    }

                    g.setColor(new Color(240, 240, 240));
                    g.drawLine(xOffset, y, xOffset, y + ROW_HEIGHT);
                    xOffset += COL_WIDTH;
                }

                g.setColor(new Color(230, 230, 230));
                g.drawLine(LABEL_WIDTH, y + ROW_HEIGHT, getWidth(), y + ROW_HEIGHT);
            }
        }

        private String findInstructionAt(int cycle, String stage,
                java.util.List<PipelineSimulator.ExecutionStep> history,
                PipelineSimulator sim) {
            // Optimization: If it's the current cycle, check live registers first
            if (cycle == sim.getCycles()) {
                PipelineRegisters regs = sim.getPipelineRegisters();
                int stepId = -1;
                switch (stage) {
                    case "WB":
                        stepId = regs.mem_wb.stepId;
                        break;
                    case "MEM":
                        stepId = regs.ex_mem.stepId;
                        break;
                    case "EX":
                        stepId = regs.id_ex.stepId;
                        break;
                    case "ID":
                        stepId = regs.if_id.stepId;
                        break;
                    case "IF":
                        if (!sim.isDone())
                            return getInstructionString(RegisterFile.getProgramCounter());
                        break;
                }
                if (stepId != -1 && stepId < history.size()) {
                    return getInstructionString(history.get(stepId).pc);
                }
            }

            // Search history
            // Since we usually have a localized cycle range,
            // the instructions are roughly in order of cycles they appeared.
            // For better performance, we could binary search or map it.
            // But for ~1000 instructions, linear scan is fine.
            for (int i = history.size() - 1; i >= 0; i--) {
                PipelineSimulator.ExecutionStep step = history.get(i);
                if (stage.equals(step.getStageAt(cycle))) {
                    return getInstructionString(step.pc);
                }
            }
            return null;
        }

        private String getInstructionString(int pc) {
            try {
                mars.ProgramStatement stmt = Globals.memory.getStatement(pc);
                if (stmt != null)
                    return stmt.getPrintableBasicAssemblyStatement(16);
            } catch (Exception e) {
            }
            return "0x" + Integer.toHexString(pc);
        }

        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        public int getScrollableUnitIncrement(Rectangle vis, int orient, int dir) {
            return 22;
        }

        public int getScrollableBlockIncrement(Rectangle vis, int orient, int dir) {
            return 110;
        }

        public boolean getScrollableTracksViewportWidth() {
            return false;
        }

        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }
}
