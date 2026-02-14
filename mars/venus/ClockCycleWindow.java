package mars.venus;

import mars.*;
import mars.mips.hardware.RegisterFile;
import mars.simulator.*;
import javax.swing.*;
import java.awt.*;
import java.util.*;
import java.util.concurrent.locks.*;

/**
 * Window to display the Clock Cycle Diagram for the pipeline.
 * Uses a custom incremental rendering engine with sticky headers for high
 * performance.
 */
public class ClockCycleWindow extends JInternalFrame implements Observer {
    private MainDiagramPanel mainPanel;
    private RowHeaderPanel rowHeader;
    private ColumnHeaderPanel colHeader;
    private JScrollPane scrollPane;

    private static final int ROW_HEIGHT = 22;
    private static final int COL_WIDTH = 50;
    private static final int LABEL_WIDTH = 180;
    private static final int HEADER_HEIGHT = 25;

    // UI Throttling to prevent EDT starvation
    private long lastUpdateTime = 0;
    private static final long UPDATE_THROTTLE_MS = 100;
    private volatile boolean updatePending = false;

    public ClockCycleWindow() {
        super("时钟周期图", true, false, true, true);

        mainPanel = new MainDiagramPanel();
        rowHeader = new RowHeaderPanel();
        colHeader = new ColumnHeaderPanel();

        scrollPane = new JScrollPane(mainPanel);
        scrollPane.setRowHeaderView(rowHeader);
        scrollPane.setColumnHeaderView(colHeader);

        // Custom corner for instructions
        JPanel corner = new JPanel() {
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                g.setColor(new Color(240, 240, 240));
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(Color.GRAY);
                g.drawRect(0, 0, getWidth() - 1, getHeight() - 1);
                g.setColor(Color.BLACK);
                g.setFont(new Font("SansSerif", Font.PLAIN, 11));
                g.drawString(" 指令 / 周期", 10, 17);
            }
        };
        corner.setPreferredSize(new Dimension(LABEL_WIDTH, HEADER_HEIGHT));
        scrollPane.setCorner(JScrollPane.UPPER_LEFT_CORNER, corner);

        this.getContentPane().add(scrollPane);
        PipelineSimulator.getInstance().addObserver(this);
        this.setSize(800, 450);
    }

    public void update(Observable observable, Object obj) {
        if (updatePending)
            return;

        long now = System.currentTimeMillis();
        // If simulation just stopped/paused (obj != null), we force update
        if (obj == null && now - lastUpdateTime < UPDATE_THROTTLE_MS) {
            return;
        }

        updatePending = true;
        lastUpdateTime = now;

        SwingUtilities.invokeLater(() -> {
            try {
                PipelineSimulator sim = PipelineSimulator.getInstance();
                int totalCycles = sim.getCycles();
                int totalInstructions = Math.max(sim.getExecutionHistory().size(), sim.getNextStepId() + 1);

                // Update preferred sizes ONLY if they changed significantly to avoid layout
                // thrashing
                int targetWidth = totalCycles * COL_WIDTH + 100;
                int targetHeight = totalInstructions * ROW_HEIGHT + 100;

                Dimension currentSize = mainPanel.getPreferredSize();
                if (currentSize.width != targetWidth || currentSize.height != targetHeight) {
                    Dimension mainSize = new Dimension(targetWidth, targetHeight);
                    mainPanel.setPreferredSize(mainSize);
                    rowHeader.setPreferredSize(new Dimension(LABEL_WIDTH, mainSize.height));
                    colHeader.setPreferredSize(new Dimension(mainSize.width, HEADER_HEIGHT));
                    mainPanel.revalidate();
                    rowHeader.revalidate();
                    colHeader.revalidate();
                }

                mainPanel.repaint();
                rowHeader.repaint();
                colHeader.repaint();

                // Auto-scroll to the right-bottom
                JScrollBar horizontal = scrollPane.getHorizontalScrollBar();
                JScrollBar vertical = scrollPane.getVerticalScrollBar();
                if (!horizontal.getValueIsAdjusting() && !vertical.getValueIsAdjusting()) {
                    horizontal.setValue(horizontal.getMaximum());
                    vertical.setValue(vertical.getMaximum());
                }
            } finally {
                updatePending = false;
            }
        });
    }

    public void forceUpdate() {
        update(null, "FORCE"); // Use obj string to bypass throttle
    }

    /**
     * The main drawing area for the colored stage boxes.
     */
    private class MainDiagramPanel extends JPanel implements Scrollable {
        public MainDiagramPanel() {
            setOpaque(true);
            setBackground(Color.WHITE);
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
                java.util.List<PipelineSimulator.ExecutionStep> history = sim.getExecutionHistory();
                int totalCycles = sim.getCycles();
                int nextStepId = sim.getNextStepId();

                // Incremental rendering: only draw visible rows/cols
                int startRow = clip.y / ROW_HEIGHT;
                int endRow = (clip.y + clip.height) / ROW_HEIGHT + 1;
                int startCycle = clip.x / COL_WIDTH;
                int endCycle = (clip.x + clip.width) / COL_WIDTH + 1;

                // Draw background grid
                g2.setColor(new Color(245, 245, 245));
                for (int c = startCycle; c <= endCycle; c++) {
                    g2.drawLine(c * COL_WIDTH, clip.y, c * COL_WIDTH, clip.y + clip.height);
                }
                for (int r = startRow; r <= endRow; r++) {
                    g2.drawLine(clip.x, r * ROW_HEIGHT, clip.x + clip.width, r * ROW_HEIGHT);
                }

                // Draw stage boxes
                for (int r = startRow; r <= endRow; r++) {
                    int y = r * ROW_HEIGHT;
                    if (r < history.size()) {
                        PipelineSimulator.ExecutionStep step = history.get(r);
                        for (int c = startCycle; c <= Math.min(endCycle, totalCycles); c++) {
                            String stage = step.getStageAt(c);
                            if (stage != null)
                                drawStageBox(g2, c * COL_WIDTH, y, stage);
                        }
                    }
                    if (startCycle <= totalCycles && endCycle >= totalCycles) {
                        String liveStage = getLiveStage(r, sim);
                        if (liveStage != null)
                            drawStageBox(g2, totalCycles * COL_WIDTH, y, liveStage);
                    }
                }
            } finally {
                lock.readLock().unlock();
            }
        }

        private void drawStageBox(Graphics2D g, int x, int y, String stage) {
            Color fill = getStageColor(stage);
            g.setColor(fill);
            g.fillRect(x + 2, y + 2, COL_WIDTH - 4, ROW_HEIGHT - 4);
            g.setColor(Color.BLACK);
            g.drawRect(x + 2, y + 2, COL_WIDTH - 4, ROW_HEIGHT - 4);
            g.setFont(new Font("SansSerif", Font.BOLD, 10));
            g.drawString(stage, x + (COL_WIDTH - stage.length() * 7) / 2, y + 15);
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

    /**
     * Vertical axis showing instructions.
     */
    private class RowHeaderPanel extends JPanel {
        public RowHeaderPanel() {
            setOpaque(true);
            setBackground(new Color(240, 240, 240));
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Rectangle clip = g.getClipBounds();
            if (clip == null)
                return;

            PipelineSimulator sim = PipelineSimulator.getInstance();
            sim.getLock().readLock().lock();
            try {
                java.util.List<PipelineSimulator.ExecutionStep> history = sim.getExecutionHistory();
                int nextStepId = sim.getNextStepId();
                int startRow = clip.y / ROW_HEIGHT;
                int endRow = (clip.y + clip.height) / ROW_HEIGHT + 1;

                g.setColor(Color.BLACK);
                for (int r = startRow; r <= endRow; r++) {
                    int y = r * ROW_HEIGHT;
                    String text = "";
                    if (r < history.size()) {
                        text = getInstructionString(history.get(r).pc);
                    } else if (r == nextStepId && !sim.isDone()) {
                        text = getInstructionString(RegisterFile.getProgramCounter());
                    }
                    if (!text.isEmpty())
                        g.drawString(text, 5, y + 16);
                    g.setColor(Color.LIGHT_GRAY);
                    g.drawLine(0, y + ROW_HEIGHT, LABEL_WIDTH, y + ROW_HEIGHT);
                    g.setColor(Color.BLACK);
                }
                g.setColor(Color.GRAY);
                g.drawLine(LABEL_WIDTH - 1, clip.y, LABEL_WIDTH - 1, clip.y + clip.height);
            } finally {
                sim.getLock().readLock().unlock();
            }
        }
    }

    /**
     * Horizontal axis showing cycle counts.
     */
    private class ColumnHeaderPanel extends JPanel {
        public ColumnHeaderPanel() {
            setOpaque(true);
            setBackground(new Color(240, 240, 240));
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Rectangle clip = g.getClipBounds();
            if (clip == null)
                return;

            int totalCycles = PipelineSimulator.getInstance().getCycles();
            int startCycle = clip.x / COL_WIDTH;
            int endCycle = (clip.x + clip.width) / COL_WIDTH + 1;

            g.setColor(Color.BLACK);
            for (int c = startCycle; c <= Math.min(endCycle, totalCycles); c++) {
                int x = c * COL_WIDTH;
                g.drawString(String.valueOf(c), x + COL_WIDTH / 2 - 5, 17);
                g.setColor(Color.LIGHT_GRAY);
                g.drawLine(x, 0, x, HEADER_HEIGHT);
                g.setColor(Color.BLACK);
            }
            g.setColor(Color.GRAY);
            g.drawLine(clip.x, HEADER_HEIGHT - 1, clip.x + clip.width, HEADER_HEIGHT - 1);
        }
    }

    private Color getStageColor(String stage) {
        switch (stage) {
            case "IF":
                return new Color(255, 255, 0); // Yellow
            case "ID":
                return new Color(255, 200, 100); // Orange
            case "EX":
                return new Color(255, 180, 180); // Pink/Red
            case "MEM":
                return new Color(180, 255, 255); // Cyan
            case "WB":
                return new Color(180, 255, 180); // Green
            case "STALL":
                return new Color(255, 99, 71); // Tomato
            default:
                return Color.WHITE;
        }
    }

    private String getLiveStage(int row, PipelineSimulator sim) {
        PipelineRegisters regs = sim.getPipelineRegisters();
        if (row == regs.mem_wb.stepId)
            return "WB";
        if (row == regs.ex_mem.stepId)
            return "MEM";
        if (row == regs.id_ex.stepId)
            return "EX";
        if (row == regs.if_id.stepId)
            return "ID";
        if (row == sim.getNextStepId() && !sim.isDone())
            return "IF";
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
}
