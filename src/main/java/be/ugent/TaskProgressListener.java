package be.ugent;

public interface TaskProgressListener {

    void notifyProgress(String task, String message, float level);

    static void reportProgress(TaskProgressListener listener, String task, String message, float level){
        if (listener != null) {
            listener.notifyProgress(task, message, Math.max(0f,Math.min(1f,level)));
        }
    }
}
