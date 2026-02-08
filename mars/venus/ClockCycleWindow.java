package mars.venus;

import mars.*;
import mars.mips.hardware.RegisterFile;
import mars.simulator.*;
import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.util.*;

/**
 * Window to display the Clock Cycle Diagram for the pipeline.
 * Shows the relationship between instructions and clock cycles.
 * 
 * Supports live rendering of the current pipeline state.
 * 
 * @author Antigravity
 */
public class ClockCycleWindow extends JInternalFrame implements Observer {
    private JTable table;
    private JTable rowHeaderTable;
    private CycleTableModel tableModel;

    private final Color COLOR_IF = new Color(255, 255, 0); // Yellow
    private final Color COLOR_ID = new Color(255, 200, 100); // Orange
    private final Color COLOR_EX = new Color(255, 180, 180); // Light Red/Pink
    private final Color COLOR_MEM = new Color(180, 255, 255); // Light Blue/Cyan
    private final Color COLOR_WB = new Color(180, 255, 180); // Light Green
    private final Color COLOR_STALL = new Color(255, 99, 71); // Tomato

    public ClockCycleWindow() {
        super("时钟周期图", true, false, true, true);
        tableModel = new CycleTableModel();
        table = new JTable(tableModel);

        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setRowHeight(22);
        table.getTableHeader().setReorderingAllowed(false);

        // Header renderer for Cycles
        table.getTableHeader().setDefaultRenderer(new DefaultTableCellRenderer() {
            public Component getTableCellRendererComponent(JTable table, Object value,
                    boolean isSelected, boolean hasFocus, int row, int column) {
                super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                setHorizontalAlignment(SwingConstants.CENTER);
                setBackground(new Color(240, 240, 240));
                setBorder(BorderFactory.createMatteBorder(0, 0, 1, 1, Color.GRAY));
                return this;
            }
        });

        // Stage renderer
        table.setDefaultRenderer(Object.class, new StageRenderer());

        // Create the row header table (Instructions)
        rowHeaderTable = new JTable(tableModel);
        rowHeaderTable.setRowHeight(22);
        rowHeaderTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);

        // Synchronize selection
        table.setSelectionModel(rowHeaderTable.getSelectionModel());

        JScrollPane scrollPane = new JScrollPane(table);
        scrollPane.setRowHeaderView(rowHeaderTable);

        // Corner Label
        JLabel cornerLabel = new JLabel(" 指令/周期", SwingConstants.CENTER);
        cornerLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
        cornerLabel.setOpaque(true);
        cornerLabel.setBackground(new Color(240, 240, 240));
        cornerLabel.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 1, Color.GRAY));
        scrollPane.setCorner(JScrollPane.UPPER_LEFT_CORNER, cornerLabel);

        this.getContentPane().add(scrollPane);
        PipelineSimulator.getInstance().addObserver(this);
        syncColumns();
        this.pack();
    }

    public void update(Observable observable, Object obj) {
        tableModel.updateHistory();
        tableModel.fireTableStructureChanged();
        syncColumns();
        
        // Auto-scroll to the latest instruction (bottom) and cycle (right)
        SwingUtilities.invokeLater(() -> {
            int lastRow = table.getRowCount() - 1;
            int lastCol = table.getColumnCount() - 1;
            if (lastRow >= 0 && lastCol >= 0) {
                Rectangle rect = table.getCellRect(lastRow, lastCol, true);
                table.scrollRectToVisible(rect);
            }
        });

        repaint();
    }

    private void syncColumns() {
        // Handle Row Header Table Columns
        while (rowHeaderTable.getColumnCount() > 0) {
            rowHeaderTable.removeColumn(rowHeaderTable.getColumnModel().getColumn(0));
        }

        TableColumn instructionCol = new TableColumn(0);
        instructionCol.setHeaderValue(tableModel.getColumnName(0));
        instructionCol.setPreferredWidth(180);
        instructionCol.setCellRenderer(new DefaultTableCellRenderer() {
            public Component getTableCellRendererComponent(JTable table, Object value,
                    boolean isSelected, boolean hasFocus, int row, int column) {
                super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                setBackground(new Color(240, 240, 240));
                setBorder(BorderFactory.createMatteBorder(0, 0, 1, 1, Color.GRAY));
                return this;
            }
        });
        rowHeaderTable.addColumn(instructionCol);

        // Handle Main Table Columns (Cycles)
        for (int i = 0; i < table.getColumnCount(); i++) {
            if (table.getColumnModel().getColumn(i).getModelIndex() == 0) {
                table.removeColumn(table.getColumnModel().getColumn(i));
                break;
            }
        }

        for (int i = 0; i < table.getColumnCount(); i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(50);
        }

        rowHeaderTable.setPreferredScrollableViewportSize(new Dimension(180, 0));
    }

    private class CycleTableModel extends AbstractTableModel {
        private java.util.List<PipelineSimulator.ExecutionStep> history = new ArrayList<>();
        private int currentCycles = 0;

        public void updateHistory() {
            PipelineSimulator sim = PipelineSimulator.getInstance();
            history = new ArrayList<>(sim.getExecutionHistory());
            currentCycles = sim.getCycles();
        }

        public int getRowCount() {
            // Include the "upcoming" instruction if it's not yet in history
            return Math.max(history.size(), PipelineSimulator.getInstance().getNextStepId() + 1);
        }

        public int getColumnCount() {
            return currentCycles + 2; // +1 for label, +1 for cycle index
        }

        public Object getValueAt(int row, int col) {
            if (row < 0)
                return null;

            // Col 0 is Instruction Label
            if (col == 0) {
                if (row < history.size()) {
                    return formatInstruction(history.get(row));
                } else {
                    // Current fetch peek
                    return formatCurrentFetch();
                }
            }

            int cycleIndex = col - 1;

            // If it's in history, return the recorded stage
            if (row < history.size()) {
                String stage = history.get(row).cycleStages.get(cycleIndex);
                if (stage != null)
                    return stage;
            }

            // Otherwise, check if it's the "Live" stage for the current cycle
            if (cycleIndex == currentCycles) {
                return getLiveStage(row);
            }

            return null;
        }

        private String getLiveStage(int row) {
            PipelineSimulator sim = PipelineSimulator.getInstance();
            PipelineRegisters regs = sim.getPipelineRegisters();

            if (row == regs.mem_wb.stepId)
                return "WB";
            if (row == regs.ex_mem.stepId)
                return "MEM";
            if (row == regs.id_ex.stepId)
                return "EX";
            if (row == regs.if_id.stepId)
                return "ID"; // Not perfectly handling stalls here yet

            // For the next fetch
            if (row == sim.getNextStepId() && !sim.isDone())
                return "IF";

            return null;
        }

        private String formatInstruction(PipelineSimulator.ExecutionStep step) {
            try {
                mars.ProgramStatement stmt = Globals.memory.getStatement(step.pc);
                if (stmt != null) {
                    return stmt.getPrintableBasicAssemblyStatement(16);
                }
            } catch (Exception e) {
            }
            return step.instruction;
        }

        private String formatCurrentFetch() {
            int pc = RegisterFile.getProgramCounter();
            try {
                mars.ProgramStatement stmt = Globals.memory.getStatement(pc);
                if (stmt != null) {
                    return stmt.getPrintableBasicAssemblyStatement(16);
                }
            } catch (Exception e) {
            }
            return "nop";
        }

        public String getColumnName(int col) {
            if (col == 0)
                return "指令/周期";
            return String.valueOf(col - 1);
        }
    }

    private class StageRenderer extends DefaultTableCellRenderer {
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean isSelected, boolean hasFocus, int row, int column) {
            super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);

            String stage = (String) value;
            setHorizontalAlignment(SwingConstants.CENTER);
            setForeground(Color.BLACK);

            if (stage == null) {
                setBackground(Color.WHITE);
                setText("");
            } else {
                setText(stage);
                switch (stage) {
                    case "IF":
                        setBackground(COLOR_IF);
                        break;
                    case "ID":
                        setBackground(COLOR_ID);
                        break;
                    case "EX":
                        setBackground(COLOR_EX);
                        break;
                    case "MEM":
                        setBackground(COLOR_MEM);
                        break;
                    case "WB":
                        setBackground(COLOR_WB);
                        break;
                    case "STALL":
                        setBackground(COLOR_STALL);
                        break;
                    default:
                        setBackground(Color.WHITE);
                }
            }
            return this;
        }
    }
}
