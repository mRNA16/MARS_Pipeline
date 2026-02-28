package mars.simulator;

import mars.*;
import mars.mips.hardware.*;

import java.util.*;
import java.util.concurrent.locks.*;
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

    // Statistics
    private int cycles = 0;
    private int nextStepId = 0;
    private List<ExecutionStep> executionHistory = new ArrayList<>();
    private final ReentrantReadWriteLock stateLock = new ReentrantReadWriteLock();

    // Hazard information for visualization
    public static class HazardInfo {
        public boolean stalled = false;
        public String stallSource = null; // "EX" or "MEM"
        public int stallReg = -1; // The register that caused the stall

        public static class ForwardData {
            public String srcStage;
            public int regNum;

            public ForwardData(String s, int r) {
                srcStage = s;
                regNum = r;
            }
        }

        public Map<String, ForwardData> forwardings = new HashMap<>();

        public void clear() {
            stalled = false;
            stallSource = null;
            stallReg = -1;
            forwardings.clear();
        }
    }

    private HazardInfo currentHazard = new HazardInfo();

    public static class ExecutionStep {
        public int id;
        public int pc;
        public int cycleIF = -1;
        public int cycleID = -1;
        public int cycleEX = -1;
        public int cycleMEM = -1;
        public int cycleWB = -1;

        public List<Integer> stallCycles = null;

        public ExecutionStep(int id, int pc) {
            this.id = id;
            this.pc = pc;
        }

        public synchronized void setStage(int cycle, String stage) {
            switch (stage) {
                case "IF":
                    cycleIF = cycle;
                    break;
                case "ID":
                    cycleID = cycle;
                    break;
                case "EX":
                    cycleEX = cycle;
                    break;
                case "MEM":
                    cycleMEM = cycle;
                    break;
                case "WB":
                    cycleWB = cycle;
                    break;
                case "STALL":
                    if (stallCycles == null)
                        stallCycles = new ArrayList<>();
                    stallCycles.add(cycle);
                    break;
            }
        }

        public synchronized String getStageAt(int cycle) {
            if (cycle == cycleIF)
                return "IF";
            if (cycle == cycleID)
                return "ID";
            if (cycle == cycleEX)
                return "EX";
            if (cycle == cycleMEM)
                return "MEM";
            if (cycle == cycleWB)
                return "WB";
            if (stallCycles != null && stallCycles.contains(cycle))
                return "STALL";
            return null;
        }

        public synchronized void undoCycle(int cycle) {
            if (cycleIF == cycle)
                cycleIF = -1;
            if (cycleID == cycle)
                cycleID = -1;
            if (cycleEX == cycle)
                cycleEX = -1;
            if (cycleMEM == cycle)
                cycleMEM = -1;
            if (cycleWB == cycle)
                cycleWB = -1;
            if (stallCycles != null) {
                stallCycles.removeIf(c -> c == cycle);
            }
        }
    }

    private static class PipelineSnapshot {
        public int D_pc, D_instr, D_stepId;
        public int E_pc, E_instr, E_RD1, E_RD2, E_EXT, E_stepId;
        public boolean E_zero;
        public int M_pc, M_instr, M_RD2, M_alu_ans, M_stepId;
        public boolean M_zero;
        public int W_pc, W_instr, W_alu_ans, W_dm_read, W_stepId;
        public boolean W_zero;
        public int snap_nextStepId;
        public int archPC;

        public PipelineSnapshot(PipelineRegisters regs, int nextStepId) {
            D_pc = regs.if_id.D_pc;
            D_instr = regs.if_id.D_instr;
            D_stepId = regs.if_id.stepId;

            E_pc = regs.id_ex.E_pc;
            E_instr = regs.id_ex.E_instr;
            E_RD1 = regs.id_ex.E_RD1;
            E_RD2 = regs.id_ex.E_RD2;
            E_EXT = regs.id_ex.E_EXT;
            E_zero = regs.id_ex.E_zero;
            E_stepId = regs.id_ex.stepId;

            M_pc = regs.ex_mem.M_pc;
            M_instr = regs.ex_mem.M_instr;
            M_RD2 = regs.ex_mem.M_RD2;
            M_alu_ans = regs.ex_mem.M_alu_ans;
            M_zero = regs.ex_mem.M_zero;
            M_stepId = regs.ex_mem.stepId;

            W_pc = regs.mem_wb.W_pc;
            W_instr = regs.mem_wb.W_instr;
            W_alu_ans = regs.mem_wb.W_alu_ans;
            W_dm_read = regs.mem_wb.W_dm_read;
            W_zero = regs.mem_wb.W_zero;
            W_stepId = regs.mem_wb.stepId;

            this.snap_nextStepId = nextStepId;
            this.archPC = RegisterFile.getProgramCounter();
        }

        public void restore(PipelineRegisters regs) {
            regs.if_id.D_pc = D_pc;
            regs.if_id.D_instr = D_instr;
            regs.if_id.stepId = D_stepId;

            regs.id_ex.E_pc = E_pc;
            regs.id_ex.E_instr = E_instr;
            regs.id_ex.E_RD1 = E_RD1;
            regs.id_ex.E_RD2 = E_RD2;
            regs.id_ex.E_EXT = E_EXT;
            regs.id_ex.E_zero = E_zero;
            regs.id_ex.stepId = E_stepId;

            regs.ex_mem.M_pc = M_pc;
            regs.ex_mem.M_instr = M_instr;
            regs.ex_mem.M_RD2 = M_RD2;
            regs.ex_mem.M_alu_ans = M_alu_ans;
            regs.ex_mem.M_zero = M_zero;
            regs.ex_mem.stepId = M_stepId;

            regs.mem_wb.W_pc = W_pc;
            regs.mem_wb.W_instr = W_instr;
            regs.mem_wb.W_alu_ans = W_alu_ans;
            regs.mem_wb.W_dm_read = W_dm_read;
            regs.mem_wb.W_zero = W_zero;
            regs.mem_wb.stepId = W_stepId;

            RegisterFile.initializeProgramCounter(archPC);
        }
    }

    private Stack<PipelineSnapshot> snapshotStack = new Stack<>();
    private Stack<Integer> backstepCountStack = new Stack<>();

    public ReentrantReadWriteLock getLock() {
        return stateLock;
    }

    public List<ExecutionStep> getExecutionHistory() {
        stateLock.readLock().lock();
        try {
            return new ArrayList<>(executionHistory);
        } finally {
            stateLock.readLock().unlock();
        }
    }

    public int getCycles() {
        stateLock.readLock().lock();
        try {
            return cycles;
        } finally {
            stateLock.readLock().unlock();
        }
    }

    public int getNextStepId() {
        stateLock.readLock().lock();
        try {
            return nextStepId;
        } finally {
            stateLock.readLock().unlock();
        }
    }

    public HazardInfo getHazardInfo() {
        stateLock.readLock().lock();
        try {
            // Return a copy to avoid concurrency issues during UI paint
            HazardInfo copy = new HazardInfo();
            copy.stalled = currentHazard.stalled;
            copy.stallSource = currentHazard.stallSource;
            copy.stallReg = currentHazard.stallReg;
            copy.forwardings.putAll(currentHazard.forwardings);
            return copy;
        } finally {
            stateLock.readLock().unlock();
        }
    }

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
        stateLock.writeLock().lock();
        try {
            regs.resetAll();
            cycles = 0;
            nextStepId = 0;
            executionHistory.clear();
            snapshotStack.clear();
            backstepCountStack.clear();
            currentHazard.clear();
            updateHazardInfo();
            setChanged();
        } finally {
            stateLock.writeLock().unlock();
        }
        notifyObservers("RESET");
    }

    /**
     * Simulation step for external call (with action)
     */
    public boolean step(AbstractAction action) throws ProcessingException {
        boolean done = false;
        stateLock.writeLock().lock();
        try {
            // Save state BEFORE change
            snapshotStack.push(new PipelineSnapshot(regs, nextStepId));
            int backstepSizeBefore = 0;
            if (Globals.getSettings().getBackSteppingEnabled() && Globals.program.getBackStepper() != null) {
                backstepSizeBefore = Globals.program.getBackStepper().getStackSize();
            }

            done = simulateStructure();

            if (Globals.getSettings().getBackSteppingEnabled() && Globals.program.getBackStepper() != null) {
                int backstepSizeAfter = Globals.program.getBackStepper().getStackSize();
                backstepCountStack.push(backstepSizeAfter - backstepSizeBefore);
            } else {
                backstepCountStack.push(0);
            }

            cycles++;
            setChanged();
        } finally {
            stateLock.writeLock().unlock();
        }
        notifyObservers();
        Thread.yield();
        return done;
    }

    /**
     * Undo one cycle
     */
    public void back() {
        stateLock.writeLock().lock();
        try {
            if (cycles == 0 || snapshotStack.isEmpty()) {
                return;
            }

            // 1. Restore Registers
            PipelineSnapshot snap = snapshotStack.pop();
            snap.restore(regs);
            this.nextStepId = snap.snap_nextStepId;

            // 2. Undo Architectural State
            if (Globals.getSettings().getBackSteppingEnabled() && Globals.program.getBackStepper() != null) {
                int count = backstepCountStack.pop();
                Globals.program.getBackStepper().backStepRaw(count);
            } else {
                backstepCountStack.pop();
            }

            // 3. Rollback History
            cycles--;
            int targetCycle = cycles; // This is the cycles count (0-indexed) during the step we are undoing

            for (ExecutionStep step : executionHistory) {
                step.undoCycle(targetCycle);
            }

            // Revert instructions that were first fetched/added in the cycle we just undid
            if (executionHistory.size() > nextStepId) {
                executionHistory.subList(nextStepId, executionHistory.size()).clear();
            }

            updateHazardInfo();
            setChanged();
        } finally {
            stateLock.writeLock().unlock();
        }
        notifyObservers();
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
            forward_M_RT_Value = W_WD;
        }

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
        boolean branchTaken = true;

        if (D_ctrl.npc_op == PipelineController.NPC_BRANCH && branch_condition) {
            npc = dpc_4 + ((short) (D.D_instr & 0xFFFF) << 2); // short to int will sign-extend automatically
        } else if (D_ctrl.npc_op == PipelineController.NPC_JUMP) {
            npc = (dpc_4 & 0xF0000000) | ((D.D_instr & 0x03FFFFFF) << 2);
        } else if (D_ctrl.npc_op == PipelineController.NPC_JR) {
            npc = D_RD1;
        } else {
            branchTaken = false;
        }

        // --- IF Stage Logic ---
        int F_pc = RegisterFile.getProgramCounter();
        int F_instr = fetchInstruction(F_pc);

        // ================= UPDATE ARCHITECTURAL STATE (GRF, DM, PC) =================

        // Write Back (WB)
        if (W_ctrl.grf_we && W_A3 != 0) {
            RegisterFile.updateRegister(W_A3, W_WD);
        }

        // Memory (MEM)
        int M_ReadVal = 0;
        if (M_ctrl.dm_we) {
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

        // 3. History Tracking
        // Record current locations before we update registers
        if (W.stepId != -1) {
            ExecutionStep s = executionHistory.get(W.stepId);
            if (s != null)
                s.setStage(cycles, "WB");
        }
        if (M.stepId != -1) {
            ExecutionStep s = executionHistory.get(M.stepId);
            if (s != null)
                s.setStage(cycles, "MEM");
        }
        if (E.stepId != -1) {
            ExecutionStep s = executionHistory.get(E.stepId);
            if (s != null)
                s.setStage(cycles, "EX");
        }
        if (D.stepId != -1) {
            ExecutionStep s = executionHistory.get(D.stepId);
            if (s != null)
                s.setStage(cycles, stall ? "STALL" : "ID");
        }

        int F_stepId = -1;
        if (!stall) {
            mars.ProgramStatement F_stmt = null;
            try {
                F_stmt = Globals.memory.getStatement(F_pc);
            } catch (Exception e) {
            }

            if (F_stmt != null) {
                F_stepId = nextStepId++;
                ExecutionStep step = new ExecutionStep(F_stepId, F_pc);
                step.setStage(cycles, "IF");
                executionHistory.add(step);
            }
        }

        // 4. Pipeline Register Updates

        // MEM/WB
        regs.mem_wb.update(M.M_pc, M.M_instr, M.M_alu_ans, M_ReadVal, M.M_zero, M.stepId);

        // EX/MEM
        regs.ex_mem.update(E.E_pc, E.E_instr, AluB, E_AluRes, E.E_zero, E.stepId);

        // ID/EX or Stall (Bubble)
        if (stall) {
            // Insert Bubble into EX
            regs.id_ex.reset();
        } else {
            // Pass D to E
            int extImm = D_ctrl.imm16;
            if (D_ctrl.ext_op == 1)
                extImm = (short) extImm;
            regs.id_ex.update(D.D_pc, D.D_instr, D_RD1, D_RD2, extImm, branch_condition, D.stepId);
        }

        // IF/ID or Stall (Bubble)
        if (!stall) {
            int instrToID = F_instr;
            int stepIdToID = F_stepId;
            if (branchTaken && !Globals.getSettings().getBooleanSetting(Settings.DELAYED_BRANCHING_ENABLED)) {
                instrToID = 0; // Flush
                stepIdToID = -1;
            }

            regs.if_id.update(F_pc, instrToID, stepIdToID);
            // Update PC - temporarily disable backstepper recording for PC here
            // because we manage PC explicitly via cycle internal state/snapshots
            BackStepper bs = Globals.program.getBackStepper();
            boolean bsEngaged = bs != null && bs.enabled();
            if (bsEngaged)
                bs.setEnabled(false);
            RegisterFile.setProgramCounter(npc);
            if (bsEngaged)
                bs.setEnabled(true);
        }

        // --- Update Hazard Info for the NEW state displayed in UI ---
        updateHazardInfo();

        return isDone();
    }

    /**
     * Internal method to calculate hazards based on the CURRENT state of pipeline
     * registers.
     * This ensures UI visualization matches the active "wires" between stages.
     */
    private void updateHazardInfo() {
        stateLock.writeLock().lock();
        try {
            currentHazard.clear();

            PipelineRegisters.IF_ID D = regs.if_id;
            PipelineRegisters.ID_EX E = regs.id_ex;
            PipelineRegisters.EX_MEM M = regs.ex_mem;
            PipelineRegisters.MEM_WB W = regs.mem_wb;

            PipelineController.Signals D_ctrl = PipelineController.decode(D.D_instr);
            PipelineController.Signals E_ctrl = PipelineController.decode(E.E_instr);
            PipelineController.Signals M_ctrl = PipelineController.decode(M.M_instr);
            PipelineController.Signals W_ctrl = PipelineController.decode(W.W_instr);

            // 1. Detect Stalls (Are the current instructions in D/E/M causing a stall for
            // next cycle?)
            boolean stall = HazardUnit.checkStall(
                    D_ctrl.t_use_rs, D_ctrl.t_use_rt,
                    E_ctrl.t_new_E, M_ctrl.t_new_M, W_ctrl.t_new_W,
                    D_ctrl.rs, D_ctrl.rt, E_ctrl.writeRegDst, M_ctrl.writeRegDst);

            if (stall) {
                currentHazard.stalled = true;
                boolean rs_E_hazard = (D_ctrl.t_use_rs < E_ctrl.t_new_E) && (D_ctrl.rs == E_ctrl.writeRegDst)
                        && (E_ctrl.writeRegDst != 0);
                boolean rt_E_hazard = (D_ctrl.t_use_rt < E_ctrl.t_new_E) && (D_ctrl.rt == E_ctrl.writeRegDst)
                        && (E_ctrl.writeRegDst != 0);
                currentHazard.stallSource = (rs_E_hazard || rt_E_hazard) ? "EX" : "MEM";

                // Track which register caused the stall
                if (rs_E_hazard)
                    currentHazard.stallReg = D_ctrl.rs;
                else if (rt_E_hazard)
                    currentHazard.stallReg = D_ctrl.rt;
                else {
                    boolean rs_M_hazard = (D_ctrl.t_use_rs < M_ctrl.t_new_M) && (D_ctrl.rs == M_ctrl.writeRegDst)
                            && (M_ctrl.writeRegDst != 0);
                    if (rs_M_hazard)
                        currentHazard.stallReg = D_ctrl.rs;
                    else
                        currentHazard.stallReg = D_ctrl.rt;
                }
            }

            // 2. Detect Forwardings (Based on visible instructions)
            // Destination: ID
            if (D_ctrl.rs != 0) {
                if (D_ctrl.rs == M_ctrl.writeRegDst && M_ctrl.grf_we)
                    currentHazard.forwardings.put("ID_RS", new HazardInfo.ForwardData("MEM", D_ctrl.rs));
                else if (D_ctrl.rs == W_ctrl.writeRegDst && W_ctrl.grf_we)
                    currentHazard.forwardings.put("ID_RS", new HazardInfo.ForwardData("WB", D_ctrl.rs));
            }
            if (D_ctrl.rt != 0) {
                if (D_ctrl.rt == M_ctrl.writeRegDst && M_ctrl.grf_we)
                    currentHazard.forwardings.put("ID_RT", new HazardInfo.ForwardData("MEM", D_ctrl.rt));
                else if (D_ctrl.rt == W_ctrl.writeRegDst && W_ctrl.grf_we)
                    currentHazard.forwardings.put("ID_RT", new HazardInfo.ForwardData("WB", D_ctrl.rt));
            }
            // Destination: EX
            if (E_ctrl.rs != 0) {
                if (E_ctrl.rs == M_ctrl.writeRegDst && M_ctrl.grf_we)
                    currentHazard.forwardings.put("EX_RS", new HazardInfo.ForwardData("MEM", E_ctrl.rs));
                else if (E_ctrl.rs == W_ctrl.writeRegDst && W_ctrl.grf_we)
                    currentHazard.forwardings.put("EX_RS", new HazardInfo.ForwardData("WB", E_ctrl.rs));
            }
            if (E_ctrl.rt != 0) {
                if (E_ctrl.rt == M_ctrl.writeRegDst && M_ctrl.grf_we)
                    currentHazard.forwardings.put("EX_RT", new HazardInfo.ForwardData("MEM", E_ctrl.rt));
                else if (E_ctrl.rt == W_ctrl.writeRegDst && W_ctrl.grf_we)
                    currentHazard.forwardings.put("EX_RT", new HazardInfo.ForwardData("WB", E_ctrl.rt));
            }
            // Destination: MEM (RT for Store)
            if (M_ctrl.rt != 0 && M_ctrl.rt == W_ctrl.writeRegDst && W_ctrl.grf_we) {
                currentHazard.forwardings.put("MEM_RT", new HazardInfo.ForwardData("WB", M_ctrl.rt));
            }

        } finally {
            stateLock.writeLock().unlock();
        }
    }

    /**
     * Check if the pipeline is empty and no more instructions are coming.
     */
    public boolean isDone() {
        stateLock.readLock().lock();
        try {
            return regs.if_id.D_instr == 0 && regs.if_id.stepId == -1 &&
                    regs.id_ex.E_instr == 0 && regs.id_ex.stepId == -1 &&
                    regs.ex_mem.M_instr == 0 && regs.ex_mem.stepId == -1 &&
                    regs.mem_wb.W_instr == 0 && regs.mem_wb.stepId == -1 &&
                    fetchInstruction(RegisterFile.getProgramCounter()) == 0 &&
                    fetchStatement(RegisterFile.getProgramCounter()) == null;
        } finally {
            stateLock.readLock().unlock();
        }
    }

    private int fetchInstruction(int address) {
        mars.ProgramStatement stmt = fetchStatement(address);
        return (stmt == null) ? 0 : stmt.getBinaryStatement();
    }

    private mars.ProgramStatement fetchStatement(int address) {
        try {
            return Globals.memory.getStatement(address);
        } catch (Exception e) {
            return null;
        }
    }
}
