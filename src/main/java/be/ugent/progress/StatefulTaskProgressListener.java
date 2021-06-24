package be.ugent.progress;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public abstract class StatefulTaskProgressListener implements TaskProgressListener{

    ConcurrentHashMap<String, TaskProgress> latestTaskStates = new ConcurrentHashMap<>();

    @Override
    final public void notifyProgress(String task, String message, float level) {
        doNotifyProgress(task, message, level);
        latestTaskStates.put(task, new TaskProgress(task, message, level));
    }

    public TaskProgress getState(String task) {
        return latestTaskStates.get(task);
    }

    public Set<String> getTaskNames(){
        return new HashSet(latestTaskStates.keySet());
    }

    abstract public void doNotifyProgress(String task, String message, float level);

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
    }
}
