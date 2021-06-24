package be.ugent;

public interface ProgressListener {

    void notifyProgress(String task, String message, float level);

    static void reportProgress(ProgressListener listener, String task, String message, float level){
        if (listener != null) {
            listener.notifyProgress(task, message, Math.max(0f,Math.min(1f,level)));
        }
    }
}
