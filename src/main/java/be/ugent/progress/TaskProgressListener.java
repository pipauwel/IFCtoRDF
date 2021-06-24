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

    static void reportProgress(TaskProgressListener listener, String task, String message, float level){
        if (listener != null) {
            listener.notifyProgress(task, message, Math.max(0f,Math.min(1f,level)));
        }
    }
}
