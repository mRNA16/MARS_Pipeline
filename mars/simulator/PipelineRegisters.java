package mars.simulator;

/**
 * Port of PipelineRegisters
 * Stores data latched between pipeline stages.
 */
public class PipelineRegisters {

    public static class IF_ID {
        public int D_pc;
        public int D_instr;
        public int stepId = -1;

        public void update(int F_pc, int F_instr, int stepId) {
            this.D_pc = F_pc;
            this.D_instr = F_instr;
            this.stepId = stepId;
        }

        public void reset() {
            D_pc = 0;
            D_instr = 0;
            stepId = -1;
        }
    }

    public static class ID_EX {
        public int E_pc;
        public int E_instr;
        public int E_RD1;
        public int E_RD2;
        public int E_EXT;
        public boolean E_zero;
        public int stepId = -1;

        public void update(int D_pc, int D_instr, int D_RD1, int D_RD2, int D_EXT, boolean D_zero, int stepId) {
            this.E_pc = D_pc;
            this.E_instr = D_instr;
            this.E_RD1 = D_RD1;
            this.E_RD2 = D_RD2;
            this.E_EXT = D_EXT;
            this.E_zero = D_zero;
            this.stepId = stepId;
        }

        public void reset() {
            E_pc = 0;
            E_instr = 0;
            E_RD1 = 0;
            E_RD2 = 0;
            E_EXT = 0;
            E_zero = false;
            stepId = -1;
        }
    }

    public static class EX_MEM {
        public int M_pc;
        public int M_instr;
        public int M_RD2;
        public int M_alu_ans;
        public boolean M_zero;
        public int stepId = -1;

        public void update(int E_pc, int E_instr, int E_RD2, int E_alu_ans, boolean E_zero, int stepId) {
            this.M_pc = E_pc;
            this.M_instr = E_instr;
            this.M_RD2 = E_RD2;
            this.M_alu_ans = E_alu_ans;
            this.M_zero = E_zero;
            this.stepId = stepId;
        }

        public void reset() {
            M_pc = 0;
            M_instr = 0;
            M_RD2 = 0;
            M_alu_ans = 0;
            M_zero = false;
            stepId = -1;
        }
    }

    public static class MEM_WB {
        public int W_pc;
        public int W_instr;
        public int W_alu_ans;
        public int W_dm_read;
        public boolean W_zero;
        public int stepId = -1;

        public void update(int M_pc, int M_instr, int M_alu_ans, int M_dm_read, boolean M_zero, int stepId) {
            this.W_pc = M_pc;
            this.W_instr = M_instr;
            this.W_alu_ans = M_alu_ans;
            this.W_dm_read = M_dm_read;
            this.W_zero = M_zero;
            this.stepId = stepId;
        }

        public void reset() {
            W_pc = 0;
            W_instr = 0;
            W_alu_ans = 0;
            W_dm_read = 0;
            W_zero = false;
            stepId = -1;
        }
    }

    // Instances to be used by Simulator
    public IF_ID if_id = new IF_ID();
    public ID_EX id_ex = new ID_EX();
    public EX_MEM ex_mem = new EX_MEM();
    public MEM_WB mem_wb = new MEM_WB();

    public void resetAll() {
        if_id.reset();
        id_ex.reset();
        ex_mem.reset();
        mem_wb.reset();
    }
}
