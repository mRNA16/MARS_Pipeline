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
        private static final int START_X = 40;
        private static final int START_Y = 40;

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
            Color idColor = new Color(255, 225, 180);
            Color exColor = new Color(255, 220, 220); // Milder red for EX

            // Stages: IF, ID, EX, MEM, WB
            drawStage(g2, "IF", START_X, START_Y, instrColor, getIFInstr(regs), getIFPC());
            drawStage(g2, "ID", START_X + (BOX_WIDTH + SPACING), START_Y, idColor, regs.if_id.D_instr,
                    regs.if_id.D_pc);
            drawStage(g2, "EX", START_X + 2 * (BOX_WIDTH + SPACING), START_Y, exColor, regs.id_ex.E_instr,
                    regs.id_ex.E_pc);
            drawStage(g2, "MEM", START_X + 3 * (BOX_WIDTH + SPACING), START_Y, memColor, regs.ex_mem.M_instr,
                    regs.ex_mem.M_pc);
            drawStage(g2, "WB", START_X + 4 * (BOX_WIDTH + SPACING), START_Y, regColor,
                    regs.mem_wb.W_instr, regs.mem_wb.W_pc);

            // Draw Arrows between stages
            for (int i = 0; i < 4; i++) {
                drawFlowArrow(g2, START_X + BOX_WIDTH + i * (BOX_WIDTH + SPACING), START_Y);
            }

            // Draw Hazards (Stalls and Forwarding)
            drawHazards(g2);
        }

        private void drawHazards(Graphics2D g2) {
            PipelineSimulator.HazardInfo hazard = simulator.getHazardInfo();
            if (hazard == null)
                return;

            // 1. Draw Stalls
            if (hazard.stalled) {
                int idX = getBoxX(1);
                int causeIdx = hazard.stallSource.equals("EX") ? 2 : 3;
                int causeX = getBoxX(causeIdx);

                // Highlight boxes in red
                g2.setStroke(new BasicStroke(3));
                g2.setColor(Color.RED);
                g2.drawRect(idX - 2, START_Y - 2, BOX_WIDTH + 4, BOX_HEIGHT + 4);
                g2.drawRect(causeX - 2, START_Y - 2, BOX_WIDTH + 4, BOX_HEIGHT + 4);

                // Draw red arrow on bottom, deep arch to avoid clash
                drawHazardArrow(g2, causeX + BOX_WIDTH / 2, START_Y + BOX_HEIGHT + 2,
                        idX + BOX_WIDTH / 2, START_Y + BOX_HEIGHT + 2, Color.RED, true, 45);
            }

            // 2. Draw Forwarding
            g2.setStroke(new BasicStroke(2));
            for (Map.Entry<String, String> entry : hazard.forwardings.entrySet()) {
                String dest = entry.getKey();
                String src = entry.getValue();

                int srcIdx = (src.equals("MEM")) ? 3 : 4;
                int destIdx = dest.startsWith("ID") ? 1 : (dest.startsWith("EX") ? 2 : 3);

                int srcX = getBoxX(srcIdx);
                int destX = getBoxX(destIdx);

                // Highlight boxes in blue
                g2.setColor(Color.BLUE);
                g2.drawRect(srcX - 2, START_Y - 2, BOX_WIDTH + 4, BOX_HEIGHT + 4);
                g2.drawRect(destX - 2, START_Y - 2, BOX_WIDTH + 4, BOX_HEIGHT + 4);

                // RS on top, RT on bottom
                boolean isRT = dest.endsWith("RT");
                int yPos = isRT ? (START_Y + BOX_HEIGHT + 2) : (START_Y - 2);

                // archHeight varies by distance to avoid overlapping paths
                int archHeight = Math.abs(srcIdx - destIdx) * 12 + 25;

                drawHazardArrow(g2, srcX + BOX_WIDTH / 2, yPos,
                        destX + BOX_WIDTH / 2, yPos, Color.BLUE, isRT, archHeight);
            }
        }

        private int getBoxX(int stageIdx) {
            return START_X + stageIdx * (BOX_WIDTH + SPACING);
        }

        private void drawHazardArrow(Graphics2D g2, int x1, int y1, int x2, int y2, Color color, boolean archDown,
                int arcHeight) {
            g2.setColor(color);
            int effectiveArc = archDown ? -arcHeight : arcHeight;

            // Draw a curved line (quad curve)
            int ctrlX = (x1 + x2) / 2;
            int ctrlY = (y1 + y2) / 2 - effectiveArc;

            g2.draw(new java.awt.geom.QuadCurve2D.Float(x1, y1, ctrlX, ctrlY, x2, y2));

            // Arrow head at x2, y2
            int headSize = 8;
            double angle = Math.atan2(y2 - ctrlY, x2 - ctrlX);
            g2.translate(x2, y2);
            g2.rotate(angle);
            g2.drawLine(0, 0, -headSize, -headSize / 2);
            g2.drawLine(0, 0, -headSize, headSize / 2);
            g2.rotate(-angle);
            g2.translate(-x2, -y2);
        }

        private void drawFlowArrow(Graphics2D g2, int x, int y) {
            g2.setColor(Color.BLACK);
            g2.setStroke(new BasicStroke(2));
            int x1 = x;
            int x2 = x + SPACING;
            int yMid = y + BOX_HEIGHT / 2;

            // Draw line
            g2.drawLine(x1, yMid, x2, yMid);

            // Draw arrow head
            int headSize = 8;
            g2.drawLine(x2, yMid, x2 - headSize, yMid - headSize / 2);
            g2.drawLine(x2, yMid, x2 - headSize, yMid + headSize / 2);
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

            // Draw Instruction
            g2.setFont(new Font("Monospaced", Font.PLAIN, 9));
            String instrStr = getInstructionString(instr, pc);
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

        private String getInstructionString(int binary, int pc) {
            if (binary == 0)
                return "nop";
            try {
                ProgramStatement stmt = Globals.memory.getStatement(pc);
                if (stmt != null && stmt.getBinaryStatement() == binary) {
                    return stmt.getPrintableBasicAssemblyStatement(16);
                }
            } catch (Exception e) {
            }
            // Fallback
            try {
                ProgramStatement ps = new ProgramStatement(binary, pc);
                return ps.getPrintableBasicAssemblyStatement(16);
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
                return;

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
