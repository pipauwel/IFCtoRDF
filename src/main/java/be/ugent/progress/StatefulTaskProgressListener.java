package be.ugent.progress;

import org.apache.thrift.TProcessor;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public abstract class StatefulTaskProgressListener implements TaskProgressListener{

    ConcurrentHashMap<String, TaskProgress> latestTaskProgress = new ConcurrentHashMap<>();

    @Override
    final public void notifyProgress(String task, String message, float level) {
        updateLatestTaskProgress(task, message, level);
        doNotifyProgress(task, message, level);
    }

    @Override public void notifyFinished(String task) {
        updateLatestTaskProgress(task, "finished", 1f);
        doNotifyFinished(task);
    }

    private void updateLatestTaskProgress(String task, String message, float i) {
        TaskProgress progress = new TaskProgress(task, message, i);
        TaskProgress prev = latestTaskProgress.put(task, progress);
        if (prev != null) {
            progress.firstMessageTimestamp = prev.firstMessageTimestamp;
        }
    }

    public TaskProgress getTaskProgress(String task) {
        return latestTaskProgress.get(task);
    }

    public Set<String> getTaskNames(){
        return new HashSet<>(latestTaskProgress.keySet());
    }

    abstract public void doNotifyProgress(String task, String message, float level);

    abstract public void doNotifyFinished(String task);

    public static class TaskProgress {
        private String task;
        private String message;
        private float level;
        private long firstMessageTimestamp;
        private long timestamp;

        public TaskProgress(String task, String message, float level) {
            this.task = task;
            this.message = message;
            this.level = level;
            this.timestamp = this.firstMessageTimestamp = System.currentTimeMillis();
        }

        public String getTask() {
            return task;
        }

        public String getMessage() {
            return message;
        }

        public float getLevel() {
            return level;
        }

        public boolean isFinished(){
            return level >= 1;
        }

        public long getFirstMessageTimestamp() {
            return firstMessageTimestamp;
        }
    }
}
