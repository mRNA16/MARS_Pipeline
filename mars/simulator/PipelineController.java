package mars.simulator;

import mars.util.Binary;

/**
 * Port of CTRL
 * Handles decoding of instructions to generate pipeline control signals.
 */
public class PipelineController {

    // NPC Ops
    public static final int NPC_NORMAL = 0;
    public static final int NPC_JUMP = 1;
    public static final int NPC_JR = 2;
    public static final int NPC_BRANCH = 3;

    // ALU Ops
    public static final int ALU_ADD = 0;
    public static final int ALU_SUB = 1;
    public static final int ALU_ORI = 2;
    public static final int ALU_SLL = 3;
    public static final int ALU_LUI = 4;

    public static class Signals {
        public int opCode;
        public int funcCode;
        public int rs;
        public int rt;
        public int rd;
        public int shamt;
        public int imm16;
        public int imm26;

        public int npc_op;
        public int alu_op;
        public boolean grf_we;
        public boolean dm_we;
        public int ext_op; // 0: zero, 1: sign
        public boolean alu_src; // ALUSrcSelect

        public int t_use_rs;
        public int t_use_rt;
        public int t_new_E;
        public int t_new_M;
        public int t_new_W;

        public boolean jump_and_link;

        public int writeRegDst; // 0: rt, 1: rd, 2: $31 (jal)
    }

    public static Signals decode(int instr) {
        Signals s = new Signals();

        int opCode = (instr >>> 26) & 0x3F;
        int funcCode = instr & 0x3F;
        s.opCode = opCode;
        s.funcCode = funcCode;
        s.rs = (instr >>> 21) & 0x1F;
        s.rt = (instr >>> 16) & 0x1F;
        s.rd = (instr >>> 11) & 0x1F;
        s.shamt = (instr >>> 6) & 0x1F;
        s.imm16 = instr & 0xFFFF;
        s.imm26 = instr & 0x3FFFFFF;

        // Decoding logical signals
        boolean add = (opCode == 0 && funcCode == 0x20);
        boolean sub = (opCode == 0 && funcCode == 0x22);
        boolean sll = (opCode == 0 && funcCode == 0x00);
        boolean jr = (opCode == 0 && funcCode == 0x08);

        boolean ori = (opCode == 0x0D);
        boolean lui = (opCode == 0x0F);
        boolean beq = (opCode == 0x04);
        boolean lw = (opCode == 0x23);
        boolean sw = (opCode == 0x2B);
        boolean j = (opCode == 0x02);
        boolean jal = (opCode == 0x03);

        // npc_op
        if (add || sub || sll || ori || lui || lw || sw)
            s.npc_op = NPC_NORMAL; // 0
        else if (j || jal)
            s.npc_op = NPC_JUMP; // 1
        else if (jr)
            s.npc_op = NPC_JR; // 2
        else if (beq)
            s.npc_op = NPC_BRANCH; // 3
        else
            s.npc_op = NPC_NORMAL; // default

        // grf_we (RegWrite)
        // Logic: Write if IT IS one of these, else 0.
        if (add || sub || sll || ori || lui || lw || jal)
            s.grf_we = true;
        else
            s.grf_we = false;

        // alu_op
        if (sub)
            s.alu_op = ALU_SUB;
        else if (sll)
            s.alu_op = ALU_SLL;
        else if (ori)
            s.alu_op = ALU_ORI;
        else if (lui)
            s.alu_op = ALU_LUI;
        else
            s.alu_op = ALU_ADD;

        // dm_we (MemWrite)
        s.dm_we = sw;

        // ext_op (1 for sign ext, 0 for zero ext?)
        // 1 implies Sign Extension usually. 0 implies Zero Extension.
        s.ext_op = (lw || sw || beq) ? 1 : 0;

        // alu_slt (ALU Source B selection: 1 if Immediate, 0 if RegB)
        s.alu_src = (ori || lui || lw || sw);

        // T_use_rs
        if (add || sub || ori || lw || sw)
            s.t_use_rs = 1;
        else if (beq || jr)
            s.t_use_rs = 0;
        else
            s.t_use_rs = 3; // 3 means "not used" effectively large number

        // T_use_rt
        if (add || sub || sll)
            s.t_use_rt = 1;
        else if (sw)
            s.t_use_rt = 2;
        else if (beq)
            s.t_use_rt = 0;
        else
            s.t_use_rt = 3;

        // T_new_E
        if (add || sub || sll || ori || lui)
            s.t_new_E = 1;
        else if (lw)
            s.t_new_E = 2;
        else
            s.t_new_E = 0;

        // T_new_M
        if (lw)
            s.t_new_M = 1;
        else
            s.t_new_M = 0;

        // T_new_W = 0
        s.t_new_W = 0;

        // Write Register Destination Logic (A)
        if (add || sub || sll)
            s.writeRegDst = s.rd;
        else if (ori || lui || lw)
            s.writeRegDst = s.rt;
        else if (jal)
            s.writeRegDst = 31;
        else
            s.writeRegDst = 0;

        // jump_and_link
        s.jump_and_link = jal;

        return s;
    }
}
