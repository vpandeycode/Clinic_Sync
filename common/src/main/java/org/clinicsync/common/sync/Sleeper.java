package org.clinicsync.common.sync;

/**
 * Injectable sleep primitive so retry/backoff tests never actually wait.
 */
@FunctionalInterface
public interface Sleeper {

    Sleeper REAL = Thread::sleep;

    void sleep(long millis) throws InterruptedException;
}
