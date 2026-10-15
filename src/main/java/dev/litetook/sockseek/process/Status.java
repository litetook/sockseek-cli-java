package dev.litetook.sockseek.process;

/**
 * How one sockseek process ended.
 *
 * <p>FAILED is the everyday case - sockseek exits non-zero whenever a single track fails - while
 * TIMEOUT and ERROR mean the tool itself got nowhere. Treating them alike was a real mistake
 * once: a run that never started reported every track as "not on the network".
 */
public enum Status {
    OK, FAILED, TIMEOUT, ERROR
}
