package be.ugent.progress;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public abstract class StatefulTaskProgressListener implements TaskProgressListener{

    ConcurrentHashMap<String, TaskProgress> latestTaskProgress = new ConcurrentHashMap<>();

    @Override
    final public void notifyProgress(String task, String message, float level) {
        latestTaskProgress.put(task, new TaskProgress(task, message, level));
        doNotifyProgress(task, message, level);
    }

    @Override public void notifyFinished(String task) {
        latestTaskProgress.put(task, new TaskProgress(task, "finished", 1));
        doNotifyFinished(task);
    }

    public TaskProgress getTaskProgress(String task) {
        return latestTaskProgress.get(task);
    }

    public Set<String> getTaskNames(){
        return new HashSet(latestTaskProgress.keySet());
    }

    abstract public void doNotifyProgress(String task, String message, float level);

    abstract public void doNotifyFinished(String task);

    public static class TaskProgress {
        private String task;
        private String message;
        private float level;

        public TaskProgress(String task, String message, float level) {
            this.task = task;
            this.message = message;
            this.level = level;
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
    }
}
