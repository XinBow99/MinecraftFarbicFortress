package com.xinbow99.fortressduel.jobs;

import com.xinbow99.fortressduel.util.YamlConfig;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/** jobs.yml 讀出來的職業表與節點表。 */
public final class JobRegistry {

    private volatile Map<String, JobDef> jobs = Map.of();
    private volatile Map<String, NodeDef> nodes = Map.of();
    private volatile int maxWorkers = 4;

    public void load(YamlConfig cfg) {
        Map<String, JobDef> loadedJobs = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> e : cfg.getSections("jobs").entrySet()) {
            JobDef def = JobDef.from(e.getKey(), e.getValue());
            loadedJobs.put(def.id(), def);
        }

        Map<String, NodeDef> loadedNodes = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> e : cfg.getSections("nodes").entrySet()) {
            NodeDef def = NodeDef.from(e.getKey(), e.getValue());
            loadedNodes.put(def.id(), def);
        }

        this.jobs = Map.copyOf(loadedJobs);
        this.nodes = Map.copyOf(loadedNodes);
        this.maxWorkers = Math.max(0, cfg.getInt("max_workers", 4));
    }

    public JobDef job(String id) {
        return jobs.get(id);
    }

    public NodeDef node(String id) {
        return nodes.get(id);
    }

    public Collection<JobDef> allJobs() {
        return jobs.values();
    }

    public Collection<NodeDef> allNodes() {
        return nodes.values();
    }

    /** 每一方最多雇幾個工人。 */
    public int maxWorkers() {
        return maxWorkers;
    }

    public int size() {
        return jobs.size();
    }
}
