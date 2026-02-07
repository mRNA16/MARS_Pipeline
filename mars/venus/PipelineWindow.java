package mars.venus;

import mars.*;
import mars.simulator.*;
import mars.mips.hardware.*;
import mars.util.*;
import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.util.*;

/**
 * Window to display the pipeline visualization.
 * 
 * @author Antigravity
 */
public class PipelineWindow extends JInternalFrame implements Observer {
    private PipelinePanel pipelinePanel;
    private PipelineSimulator simulator;

    public PipelineWindow() {
        super("Pipeline View", true, false, true, true);
        this.simulator = PipelineSimulator.getInstance();

        // Add as observer to both the standard simulator (for start/stop)
        // and our pipeline simulator (for cycle-by-cycle updates)
        Simulator.getInstance().addObserver(this);
        simulator.addObserver(this);

        pipelinePanel = new PipelinePanel();
        this.getContentPane().add(new JScrollPane(pipelinePanel));
        this.pack();
    }

    /**
     * Update the window. Called by Simulator observer.
     */
    public void update(Observable observable, Object obj) {
        if (obj instanceof SimulatorNotice) {
            SimulatorNotice notice = (SimulatorNotice) obj;
            if (notice.getAction() == SimulatorNotice.SIMULATOR_STOP ||
                    notice.getAction() == SimulatorNotice.SIMULATOR_START) {
                repaint();
            }
        } else {
            // General update (e.g. after a step)
            repaint();
        }
    }

    /**
     * Inner class to draw the pipeline stages.
     */
    private class PipelinePanel extends JPanel {
        private static final int BOX_WIDTH = 120;
        private static final int BOX_HEIGHT = 80;
        private static final int SPACING = 30;
        private static final int START_X = 20;
        private static final int START_Y = 20;

        public PipelinePanel() {
            setPreferredSize(new Dimension(800, 200));
            setBackground(Color.WHITE);

            addMouseListener(new MouseAdapter() {
                public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2) {
                        handleDoubleClick(e.getX(), e.getY());
                    }
                }
            });
        }

        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g;
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            PipelineRegisters regs = simulator.getPipelineRegisters();

            // Fetch colors from settings for consistency with MARS highlights
            Color instrColor = Globals.getSettings()
                    .getColorSettingByPosition(Settings.TEXTSEGMENT_HIGHLIGHT_BACKGROUND);
            Color memColor = Globals.getSettings().getColorSettingByPosition(Settings.DATASEGMENT_HIGHLIGHT_BACKGROUND);
            Color regColor = Globals.getSettings().getColorSettingByPosition(Settings.REGISTER_HIGHLIGHT_BACKGROUND);
            Color exColor = new Color(255, 220, 220); // Milder red for EX

            // Stages: IF, ID, EX, MEM, WB
            drawStage(g2, "IF", START_X, START_Y, instrColor, getIFInstr(regs), getIFPC());
            drawStage(g2, "ID", START_X + (BOX_WIDTH + SPACING), START_Y, instrColor, regs.if_id.D_instr,
                    regs.if_id.D_pc);
            drawStage(g2, "EX", START_X + 2 * (BOX_WIDTH + SPACING), START_Y, exColor, regs.id_ex.E_instr,
                    regs.id_ex.E_pc);
            drawStage(g2, "MEM", START_X + 3 * (BOX_WIDTH + SPACING), START_Y, memColor, regs.ex_mem.M_instr,
                    regs.ex_mem.M_pc);
            drawStage(g2, "WB", START_X + 4 * (BOX_WIDTH + SPACING), START_Y, regColor,
                    regs.mem_wb.W_instr, regs.mem_wb.W_pc);
        }

        private void drawStage(Graphics2D g2, String name, int x, int y, Color color, int instr, int pc) {
            // Draw box
            g2.setColor(color);
            g2.fillRect(x, y, BOX_WIDTH, BOX_HEIGHT);
            g2.setColor(Color.BLACK);
            g2.drawRect(x, y, BOX_WIDTH, BOX_HEIGHT);

            // Draw Stage Name
            g2.setFont(new Font("Arial", Font.BOLD, 14));
            g2.drawString(name, x + 5, y + 20);

            // Draw PC
            g2.setFont(new Font("Monospaced", Font.PLAIN, 12));
            String pcStr = "0x" + Binary.intToHexString(pc).toUpperCase();
            g2.drawString(pcStr, x + 5, y + 45);

            // Draw Instruction mnemonic
            String instrStr = getInstructionMnemonic(instr);
            g2.drawString(instrStr, x + 5, y + 65);
        }

        private int getIFInstr(PipelineRegisters regs) {
            // IF stage instruction is what PC points to
            try {
                mars.ProgramStatement stmt = Globals.memory.getStatement(RegisterFile.getProgramCounter());
                return (stmt == null) ? 0 : stmt.getBinaryStatement();
            } catch (Exception e) {
                return 0;
            }
        }

        private int getIFPC() {
            return RegisterFile.getProgramCounter();
        }

        private String getInstructionMnemonic(int binary) {
            if (binary == 0)
                return "nop";
            try {
                ProgramStatement ps = new ProgramStatement(binary, 0);
                if (ps.getInstruction() != null) {
                    return ps.getInstruction().getName();
                }
            } catch (Exception e) {
            }
            return "0x" + Binary.intToHexString(binary).toUpperCase();
        }

        private void handleDoubleClick(int mouseX, int mouseY) {
            // Determine which box was clicked
            int stageIdx = -1;
            for (int i = 0; i < 5; i++) {
                int x = START_X + i * (BOX_WIDTH + SPACING);
                if (mouseX >= x && mouseX <= x + BOX_WIDTH && mouseY >= START_Y && mouseY <= START_Y + BOX_HEIGHT) {
                    stageIdx = i;
                    break;
                }
            }

            if (stageIdx != -1) {
                showRegisterDetails(stageIdx);
            }
        }

        private void showRegisterDetails(int stageIdx) {
            String[] stageNames = { "IF/ID", "ID/EX", "EX/MEM", "MEM/WB" };
            if (stageIdx >= 4)
                return; // WB has no exit register displayed in this context?
                        // Actually, the request says "出口处的流水寄存器".
                        // WB's exit is the Register File update.

            String title = stageNames[stageIdx] + " Pipeline Register";
            StringBuilder details = new StringBuilder();
            PipelineRegisters regs = simulator.getPipelineRegisters();

            switch (stageIdx) {
                case 0: // IF/ID
                    details.append("D_pc: 0x").append(Binary.intToHexString(regs.if_id.D_pc)).append("\n");
                    details.append("D_instr: 0x").append(Binary.intToHexString(regs.if_id.D_instr)).append("\n");
                    break;
                case 1: // ID/EX
                    details.append("E_pc: 0x").append(Binary.intToHexString(regs.id_ex.E_pc)).append("\n");
                    details.append("E_instr: 0x").append(Binary.intToHexString(regs.id_ex.E_instr)).append("\n");
                    details.append("E_RD1: 0x").append(Binary.intToHexString(regs.id_ex.E_RD1)).append("\n");
                    details.append("E_RD2: 0x").append(Binary.intToHexString(regs.id_ex.E_RD2)).append("\n");
                    details.append("E_EXT: 0x").append(Binary.intToHexString(regs.id_ex.E_EXT)).append("\n");
                    break;
                case 2: // EX/MEM
                    details.append("M_pc: 0x").append(Binary.intToHexString(regs.ex_mem.M_pc)).append("\n");
                    details.append("M_instr: 0x").append(Binary.intToHexString(regs.ex_mem.M_instr)).append("\n");
                    details.append("M_alu_ans: 0x").append(Binary.intToHexString(regs.ex_mem.M_alu_ans)).append("\n");
                    details.append("M_RD2: 0x").append(Binary.intToHexString(regs.ex_mem.M_RD2)).append("\n");
                    break;
                case 3: // MEM/WB
                    details.append("W_pc: 0x").append(Binary.intToHexString(regs.mem_wb.W_pc)).append("\n");
                    details.append("W_instr: 0x").append(Binary.intToHexString(regs.mem_wb.W_instr)).append("\n");
                    details.append("W_alu_ans: 0x").append(Binary.intToHexString(regs.mem_wb.W_alu_ans)).append("\n");
                    details.append("W_dm_read: 0x").append(Binary.intToHexString(regs.mem_wb.W_dm_read)).append("\n");
                    break;
            }

            JOptionPane.showMessageDialog(this, details.toString(), title, JOptionPane.INFORMATION_MESSAGE);
        }
    }
}
