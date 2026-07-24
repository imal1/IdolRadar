package com.idolradar.worker;

import java.util.List;
import java.util.Optional;

/** RSS source、post 与 outbox 原子写入的持久化边界。 */
public interface FeedRepository {
    List<WorkerModels.Source> loadEnabledSources();
    void updateSourceStatus(String sourceId, WorkerModels.SourceStatus status);

    /** 原子插入 post 并登记持久化通知意图；重复 post 返回空。 */
    Optional<WorkerModels.Post> insertPostAndEnqueue(WorkerModels.Post post);
}
