package mars.simulator;

/**
 * Port of STALL
 * Handles hazard detection (Stalls).
 */
public class HazardUnit {

    /**
     * Calculates if the pipeline should stall based on the AT method.
     * 
     * @param T_use_rs Time when RS is used by the instruction in Decode stage
     * @param T_use_rt Time when RT is used by the instruction in Decode stage
     * @param T_new_E  Time when result is produced by the instruction in Execute
     *                 stage
     * @param T_new_M  Time when result is produced by the instruction in Memory
     *                 stage
     * @param T_new_W  Time when result is produced by the instruction in WriteBack
     *                 stage
     * @param D_rs     RS register number in Decode stage
     * @param D_rt     RT register number in Decode stage
     * @param E_A3     Write register number in Execute stage
     * @param M_A3     Write register number in Memory stage
     * @return true if stall is needed
     */
    public static boolean checkStall(
            int T_use_rs, int T_use_rt,
            int T_new_E, int T_new_M, int T_new_W,
            int D_rs, int D_rt,
            int E_A3, int M_A3) {

        boolean rs_E_hazard = (T_use_rs < T_new_E) && (D_rs == E_A3) && (E_A3 != 0);
        boolean rt_E_hazard = (T_use_rt < T_new_E) && (D_rt == E_A3) && (E_A3 != 0);
        boolean DE_stall = rs_E_hazard || rt_E_hazard;

        boolean rs_M_hazard = (T_use_rs < T_new_M) && (D_rs == M_A3) && (M_A3 != 0);
        boolean rt_M_hazard = (T_use_rt < T_new_M) && (D_rt == M_A3) && (M_A3 != 0);
        boolean DM_stall = rs_M_hazard || rt_M_hazard;

        return DE_stall || DM_stall;
    }
}
