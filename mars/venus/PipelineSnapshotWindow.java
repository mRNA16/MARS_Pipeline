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
    private RowHeaderPanel rowHeader;
    private ColumnHeaderPanel colHeader;
    private JScrollPane scrollPane;

    private static final int ROW_HEIGHT = 22;
    private static final int COL_WIDTH = 160;
    private static final int LABEL_WIDTH = 60;
    private static final int HEADER_HEIGHT = 25;

    private static final String[] STAGES = { "IF", "ID", "EX", "MEM", "WB" };
    private static final Color[] STAGE_COLORS = {
            new Color(255, 255, 200), // IF
            new Color(255, 230, 200), // ID
            new Color(255, 210, 210), // EX
            new Color(210, 255, 255), // MEM
            new Color(210, 255, 210) // WB
    };

    private long lastUpdateTime = 0;
    private static final long UPDATE_THROTTLE_MS = 100;
    private volatile boolean updatePending = false;

    public PipelineSnapshotWindow() {
        super("PipelineSnapshot", true, false, true, true);

        diagramPanel = new DiagramPanel();
        rowHeader = new RowHeaderPanel();
        colHeader = new ColumnHeaderPanel();

        scrollPane = new JScrollPane(diagramPanel);
        scrollPane.setRowHeaderView(rowHeader);
        scrollPane.setColumnHeaderView(colHeader);

        // Use Simple Scroll Mode to prevent ghosting from pixel copying (blit)
        scrollPane.getViewport().setScrollMode(JViewport.SIMPLE_SCROLL_MODE);
        scrollPane.getRowHeader().setScrollMode(JViewport.SIMPLE_SCROLL_MODE);

        // Header corner
        JPanel corner = new JPanel() {
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                g.setColor(new Color(240, 240, 240));
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(Color.GRAY);
                g.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
                g.setColor(Color.BLACK);
                g.setFont(new Font("SansSerif", Font.PLAIN, 11));
                g.drawString("Cycle/Stage", 0, 17);
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
                int totalCycles = PipelineSimulator.getInstance().getCycles();
                int targetWidth = STAGES.length * COL_WIDTH;
                int targetHeight = (totalCycles + 1) * ROW_HEIGHT;

                if (diagramPanel.getPreferredSize().width != targetWidth ||
                        diagramPanel.getPreferredSize().height != targetHeight) {
                    Dimension d = new Dimension(targetWidth, targetHeight);
                    diagramPanel.setPreferredSize(d);
                    rowHeader.setPreferredSize(new Dimension(LABEL_WIDTH, d.height));
                    colHeader.setPreferredSize(new Dimension(d.width, HEADER_HEIGHT));
                    diagramPanel.revalidate();
                    rowHeader.revalidate();
                    colHeader.revalidate();
                }

                diagramPanel.repaint();
                rowHeader.repaint();
                colHeader.repaint();

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

    private class ColumnHeaderPanel extends JPanel {
        public ColumnHeaderPanel() {
            setPreferredSize(new Dimension(STAGES.length * COL_WIDTH, HEADER_HEIGHT));
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            g2.setColor(new Color(240, 240, 240));
            g2.fillRect(0, 0, getWidth(), getHeight());
            g.setColor(Color.GRAY);
            g.drawLine(0, HEADER_HEIGHT - 1, getWidth(), HEADER_HEIGHT - 1);

            g.setColor(Color.BLACK);
            g.setFont(new Font("SansSerif", Font.BOLD, 12));
            for (int i = 0; i < STAGES.length; i++) {
                int x = i * COL_WIDTH;
                g.drawString(STAGES[i], x + COL_WIDTH / 2 - 10, 17);
                g.drawLine(x, 0, x, HEADER_HEIGHT);
            }
        }
    }

    private class RowHeaderPanel extends JPanel {
        public RowHeaderPanel() {
            setPreferredSize(new Dimension(LABEL_WIDTH, 400));
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int totalCycles = PipelineSimulator.getInstance().getCycles();
            g2.setColor(new Color(240, 240, 240));
            g2.fillRect(0, 0, getWidth(), getHeight());
            g.setColor(Color.GRAY);
            g.drawLine(LABEL_WIDTH - 1, 0, LABEL_WIDTH - 1, getHeight());

            g.setColor(Color.BLACK);
            g.setFont(new Font("SansSerif", Font.PLAIN, 12));
            for (int c = 0; c <= totalCycles; c++) {
                int y = c * ROW_HEIGHT;
                g.drawString("C " + c, 10, y + 16);
                g.setColor(new Color(230, 230, 230));
                g.drawLine(0, y + ROW_HEIGHT, LABEL_WIDTH, y + ROW_HEIGHT);
                g.setColor(Color.BLACK);
            }
        }
    }

    private class DiagramPanel extends JPanel implements Scrollable {
        public DiagramPanel() {
            setOpaque(true);
            setBackground(Color.WHITE);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            Rectangle clip = g.getClipBounds();
            if (clip == null)
                return;

            PipelineSimulator sim = PipelineSimulator.getInstance();
            ReentrantReadWriteLock lock = sim.getLock();

            lock.readLock().lock();
            try {
                java.util.List<PipelineSimulator.ExecutionStep> history = sim.getExecutionHistory();
                drawPipelineGrid(g2, clip, history, sim.getCycles(), sim);
            } finally {
                lock.readLock().unlock();
            }
        }

        private void drawPipelineGrid(Graphics2D g, Rectangle clip,
                java.util.List<PipelineSimulator.ExecutionStep> history,
                int totalCycles, PipelineSimulator sim) {
            int startCycle = Math.max(0, clip.y / ROW_HEIGHT);
            int endCycle = (clip.y + clip.height) / ROW_HEIGHT + 1;

            for (int c = startCycle; c <= Math.min(endCycle, totalCycles); c++) {
                int y = c * ROW_HEIGHT;
                int xOffset = 0;

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
                g.drawLine(0, y + ROW_HEIGHT, getWidth(), y + ROW_HEIGHT);
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
                        if (!sim.isDone()) {
                            int pc = RegisterFile.getProgramCounter();
                            try {
                                if (Globals.memory.getStatement(pc) != null)
                                    return getInstructionString(pc);
                            } catch (Exception e) {
                            }
                        }
                        break;
                }
                if (stepId != -1 && stepId < history.size()) {
                    return getInstructionString(history.get(stepId).pc);
                }
            }

            // Search history
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
