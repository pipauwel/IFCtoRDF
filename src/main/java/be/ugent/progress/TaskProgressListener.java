package be.ugent.progress;

public interface TaskProgressListener {
    /**
     * Informs the listener of the progress of a task.
     * 
     * @param task
     *            the name of the task
     * @param message
     *            a message about the progress
     * @param level
     *            a number between 0 and 1, 0 indicating no progress, 1
     *            indicating done
     */
    void notifyProgress(String task, String message, float level);

    /**
     * Informs the listener that the specified task is finished.
     * 
     * @param task
     *            the name of the task
     */
    void notifyFinished(String task);

}
