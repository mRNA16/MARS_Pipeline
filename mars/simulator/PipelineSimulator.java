package mars.simulator;

import mars.*;
import mars.mips.hardware.*;
import mars.util.*;
import java.util.*;
import javax.swing.AbstractAction;

/**
 * Pipeline Simulator for MIPS.
 * Implements a 5-stage pipeline: IF, ID, EX, MEM, WB.
 */
public class PipelineSimulator extends Observable {
    private static PipelineSimulator instance = null;

    // Pipeline Registers (Latch between stages)
    // We process stages in reverse order (WB->MEM->EX->ID->IF).
    private PipelineRegisters regs = new PipelineRegisters();

    // PC is special, it needs to be managed separately or part of IF stage.
    // In MARS, RegisterFile.programCounter is static. We should use it.

    // Statistics
    private int cycles = 0;

    public static PipelineSimulator getInstance() {
        if (instance == null) {
            instance = new PipelineSimulator();
        }
        return instance;
    }

    public PipelineRegisters getPipelineRegisters() {
        return regs;
    }

    private PipelineSimulator() {
    }

    /**
     * reset the pipeline and registers
     */
    public void reset() {
        regs.resetAll();
        cycles = 0;
        // Also ensure standard Registers are reset if needed, but that's usually done
        // by MarsLaunch
    }

    /**
     * Simulate one clock cycle (a step).
     * Corresponds to `always @(posedge clk)` in sequential logic.
     * We execute stages in REVERSE order: WB -> MEM -> EX -> ID -> IF
     * to prevent data from rippling through multiple stages in one cycle.
     */
    public boolean step(AbstractAction actor) throws ProcessingException {
        cycles++;
        boolean result = simulateStructure();
        setChanged();
        notifyObservers();
        return result;
    }

    // Split into structural simulation to enable "Parallel" logic
    // 1. Calculate Signals and Forwarding based on 'regs' (snapshot).
    // 2. Compute Next State.
    // 3. Update 'regs'.
    private boolean simulateStructure() {
        // ================= GET CURRENT PIPELINE REGISTERS (Old State)
        // =================
        PipelineRegisters.IF_ID D = regs.if_id;
        PipelineRegisters.ID_EX E = regs.id_ex;
        PipelineRegisters.EX_MEM M = regs.ex_mem;
        PipelineRegisters.MEM_WB W = regs.mem_wb;

        // ================= DECODE CONTROLS FOR ALL STAGES =================
        PipelineController.Signals D_ctrl = PipelineController.decode(D.D_instr);
        PipelineController.Signals E_ctrl = PipelineController.decode(E.E_instr);
        PipelineController.Signals M_ctrl = PipelineController.decode(M.M_instr);
        PipelineController.Signals W_ctrl = PipelineController.decode(W.W_instr);

        // ================= CALCULATE REGDST & WRITE DATA (COMBINATIONAL)
        // =================

        // --- WB Stage Logic (Gen WD) ---
        int W_WD = 0; // Data to write to register
        if (W_ctrl.jump_and_link) {
            W_WD = W.W_pc + 8;
        } else if (W_ctrl.opCode == 0x23) {
            W_WD = W.W_dm_read;
        } else {
            W_WD = W.W_alu_ans;
        }

        int W_A3 = W_ctrl.writeRegDst;

        // --- MEM Stage Logic (Forwarding for Store) ---
        int M_RT = M_ctrl.rt;
        int M_RD2 = M.M_RD2;

        // Forward Logic
        int forward_M_RT_Value = M_RD2;
        if (M_RT == 0) {
            forward_M_RT_Value = 0;
        } else if (W_A3 == M_RT && W_ctrl.grf_we && W_A3 != 0) {
            forward_M_RT_Value = W_WD; // Forward from WB
        }

        // int M_dm_read = 0;
        // int M_Addr = mem.M_alu_ans;

        // Forwarding to EX from MEM
        int M_WD = (M_ctrl.jump_and_link) ? M.M_pc + 8 : M.M_alu_ans;
        int M_A3 = M_ctrl.writeRegDst; // Dest for forwarding check

        // --- EX Stage Logic (Forwarding for ALU) ---
        int E_rs = E_ctrl.rs;
        int E_rt = E_ctrl.rt;

        int AluA = E.E_RD1;
        int AluB = E.E_RD2;
        // Forward Logic
        if (E_rs == 0) {
            AluA = 0;
        } else if (M_A3 == E_rs && M_ctrl.grf_we && M_A3 != 0) {
            AluA = M_WD;
        } else if (W_A3 == E_rs && W_ctrl.grf_we && W_A3 != 0) {
            AluA = W_WD;
        }

        if (E_rt == 0) {
            AluB = 0;
        } else if (M_A3 == E_rt && M_ctrl.grf_we && M_A3 != 0) {
            AluB = M_WD;
        } else if (W_A3 == E_rt && W_ctrl.grf_we && W_A3 != 0) {
            AluB = W_WD;
        }

        // ALU Execution
        int OperandA = AluA;
        int OperandB = (E_ctrl.alu_src) ? E.E_EXT : AluB; // ALUSrc mux
        int E_AluRes = 0;

        switch (E_ctrl.alu_op) {
            case PipelineController.ALU_ADD:
                E_AluRes = OperandA + OperandB;
                break;
            case PipelineController.ALU_SUB:
                E_AluRes = OperandA - OperandB;
                break;
            case PipelineController.ALU_ORI:
                E_AluRes = OperandA | OperandB;
                break;
            case PipelineController.ALU_SLL:
                E_AluRes = OperandB << E_ctrl.shamt;
                break;
            case PipelineController.ALU_LUI:
                E_AluRes = OperandB << 16;
                break;
            default:
                E_AluRes = 0;
        }

        int E_A3 = E_ctrl.writeRegDst;
        // --- ID Stage Logic (Hazard & Branch) ---
        int D_rs = D_ctrl.rs;
        int D_rt = D_ctrl.rt;

        // Read Register File
        int D_RD1 = RegisterFile.getValue(D_rs);
        int D_RD2 = RegisterFile.getValue(D_rt);

        if (D_rs != 0) {
            if (D_rs == M_A3 && M_ctrl.grf_we) {
                D_RD1 = M_WD; // Forward from M
            } else if (D_rs == W_A3 && W_ctrl.grf_we) {
                D_RD1 = W_WD; // Forward from W
            }
        } else {
            D_RD1 = 0;
        }

        if (D_rt != 0) {
            if (D_rt == M_A3 && M_ctrl.grf_we) {
                D_RD2 = M_WD;
            } else if (D_rt == W_A3 && W_ctrl.grf_we) {
                D_RD2 = W_WD;
            }
        } else {
            D_RD2 = 0;
        }

        // Detect Stall
        boolean stall = HazardUnit.checkStall(
                D_ctrl.t_use_rs, D_ctrl.t_use_rt,
                E_ctrl.t_new_E, M_ctrl.t_new_M, W_ctrl.t_new_W,
                D_rs, D_rt, E_A3, M_A3);

        // NPC Unit: Branch Logic
        boolean branch_condition = (D_RD1 == D_RD2); // only support BEQ for now
        int fpc_4 = RegisterFile.getProgramCounter() + 4;
        int dpc_4 = D.D_pc + 4;
        int npc = fpc_4;

        if (D_ctrl.npc_op == PipelineController.NPC_BRANCH && branch_condition) {
            npc = dpc_4 + ((short) (D.D_instr & 0xFFFF) << 2); // short to int will sign-extend automatically
        } else if (D_ctrl.npc_op == PipelineController.NPC_JUMP) {
            npc = (dpc_4 & 0xF0000000) | ((D.D_instr & 0x03FFFFFF) << 2);
        } else if (D_ctrl.npc_op == PipelineController.NPC_JR) {
            npc = D_RD1;
        }

        // --- IF Stage Logic ---
        int F_pc = RegisterFile.getProgramCounter();
        int F_instr = 0;
        try {
            mars.ProgramStatement stmt = Globals.memory.getStatement(F_pc);
            F_instr = (stmt == null) ? 0 : stmt.getBinaryStatement();
        } catch (AddressErrorException e) {
            F_instr = 0;
        }

        // ================= UPDATE STATE (SEQUENTIAL) =================

        // 1. WB Write
        if (W_ctrl.grf_we && W_A3 != 0) {
            RegisterFile.updateRegister(W_A3, W_WD);
        }

        // 2. Memory Access (Simulated for Side Effects)
        int M_ReadVal = 0;
        if (M_ctrl.dm_we) { // Store
            try {
                Globals.memory.setWord(M.M_alu_ans, forward_M_RT_Value);
                M_ReadVal = forward_M_RT_Value;
            } catch (Exception e) {
            }
        } else if (M_ctrl.t_new_M == 1) { // Load
            try {
                M_ReadVal = Globals.memory.getWord(M.M_alu_ans);
            } catch (Exception e) {
            }
        }

        // 3. Pipeline Register Updates

        // MEM/WB
        regs.mem_wb.update(M.M_pc, M.M_instr, M.M_alu_ans, M_ReadVal, M.M_zero);

        // EX/MEM
        regs.ex_mem.update(E.E_pc, E.E_instr, AluB, E_AluRes, E.E_zero);

        // ID/EX or Stall (Bubble)
        if (stall) {
            // Insert Bubble into EX
            regs.id_ex.reset();
        } else {
            // Pass D to E
            int extImm = D_ctrl.imm16;
            if (D_ctrl.ext_op == 1)
                extImm = (short) extImm;
            regs.id_ex.update(D.D_pc, D.D_instr, D_RD1, D_RD2, extImm, branch_condition);
        }

        // IF/ID or Stall (Bubble)
        if (!stall) {
            int instrToID = F_instr;
            boolean branchTaken = (npc != fpc_4);
            if (branchTaken && !Globals.getSettings().getDelayedBranchingEnabled()) {
                instrToID = 0; // Flush
            }

            regs.if_id.update(F_pc, instrToID);
            // If pure PC flow (no stall)
            // Update PC
            RegisterFile.setProgramCounter(npc);
        }

        return isDone();
    }

    /**
     * Check if the pipeline is empty and no more instructions are coming.
     */
    public boolean isDone() {
        return regs.if_id.D_instr == 0 &&
                regs.id_ex.E_instr == 0 &&
                regs.ex_mem.M_instr == 0 &&
                regs.mem_wb.W_instr == 0 &&
                fetchInstruction(RegisterFile.getProgramCounter()) == 0;
    }

    private int fetchInstruction(int address) {
        try {
            mars.ProgramStatement stmt = Globals.memory.getStatement(address);
            return (stmt == null) ? 0 : stmt.getBinaryStatement();
        } catch (Exception e) {
            return 0;
        }
    }
}
