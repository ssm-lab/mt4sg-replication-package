package ca.mcmaster.mt4sg.evaluation;

/**
 * Runs {@link Evaluation} with the process timer at 1 ms, so Java Flight Recorder samples at its
 * set period instead of on the default 15.6 ms Windows timer tick.
 *
 * <p>HotSpot on Windows raises the timer resolution to 1 ms during any sleep that isn't a
 * multiple of 10 ms, so a daemon thread that keeps sleeping 1 ms holds it there.
 */
public final class ProfileMain {

  private ProfileMain() {}

  public static void main(String[] args) throws Exception {
    Thread timer =
        new Thread(
            () -> {
              try {
                while (true) {
                  Thread.sleep(1);
                }
              } catch (InterruptedException e) {
                // Exits with the JVM.
              }
            },
            "timer-resolution");
    timer.setDaemon(true);
    timer.start();
    Evaluation.main(args);
  }
}
