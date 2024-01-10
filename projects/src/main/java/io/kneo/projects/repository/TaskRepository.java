package io.kneo.projects.repository;

import io.kneo.core.model.user.SuperUser;
import io.kneo.core.repository.AsyncRepository;
import io.kneo.core.repository.exception.DocumentHasNotFoundException;
import io.kneo.core.repository.exception.DocumentModificationAccessException;
import io.kneo.core.repository.rls.RLSRepository;
import io.kneo.core.repository.table.EntityData;
import io.kneo.projects.model.Task;
import io.kneo.projects.repository.table.ProjectNameResolver;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.RowSet;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static io.kneo.projects.repository.table.ProjectNameResolver.TASK;

@ApplicationScoped
public class TaskRepository extends AsyncRepository {
    private static final EntityData ENTITY_DATA = ProjectNameResolver.create().getEntityNames(TASK);
    @Inject
    private RLSRepository rlsRepository;
    private static final String BASE_REQUEST = """
            SELECT pt.*, ptr.*  FROM prj__tasks pt JOIN prj__task_readers ptr ON pt.id = ptr.entity_id\s""";

    public Uni<List<Task>> getAll(final int limit, final int offset, final long userID) {
        String sql = BASE_REQUEST + "WHERE ptr.reader = " + userID;
        if (limit > 0) {
            sql += String.format(" LIMIT %s OFFSET %s", limit, offset);
        }
        return client.query(sql)
                .execute()
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(this::from)
                .collect().asList();
    }

    public Uni<Integer> getAllCount(long userID) {
        return getAllCount(userID, ENTITY_DATA.tableName(), ENTITY_DATA.rlsName());
    }

    public Uni<Optional<Task>> findById(UUID uuid, Long userID) {
        if (uuid == null) {
            LOGGER.warn("null Id provided to find by Id");
            return Uni.createFrom().item(Optional.empty());
        }
        return client.preparedQuery(BASE_REQUEST + "WHERE ptr.reader = $1 AND pt.id = $2")
                .execute(Tuple.of(userID, uuid))
                .onItem().transform(RowSet::iterator)
                .onItem().transform(iterator -> {
                    if (iterator.hasNext()) {
                        return Optional.of(from(iterator.next()));
                    } else {
                        LOGGER.warn(String.format("No %s found with id: " + uuid, ENTITY_DATA.tableName()));
                        return Optional.empty();
                    }
                });
    }


    private Task from(Row row) {
        return new Task.Builder()
                .setId(row.getUUID("id"))
                .setAuthor(row.getLong("author"))
                .setRegDate(row.getLocalDateTime("reg_date").atZone(ZoneId.systemDefault()))
                .setLastModifier(row.getLong("last_mod_user"))
                .setLastModifiedDate(row.getLocalDateTime("last_mod_date").atZone(ZoneId.systemDefault()))
                .setRegNumber(row.getString("reg_number"))
                .setAssignee(row.getLong("assignee"))
                .setBody(row.getString("body"))
                .setProject(row.getUUID("project_id"))
                .setParent(row.getUUID("parent_id"))
                .setTaskType(row.getUUID("task_type_id"))
                .setTargetDate(Optional.ofNullable(row.getLocalDateTime("target_date"))
                        .map(dateTime -> ZonedDateTime.from(dateTime.atZone(ZoneId.systemDefault()))).orElse(null))
                .setStartDate(Optional.ofNullable(row.getLocalDateTime("start_date"))
                        .map(dateTime -> ZonedDateTime.from(dateTime.atZone(ZoneId.systemDefault()))).orElse(null))
                .setStatus(row.getInteger("status"))
                .setPriority(row.getInteger("priority"))
                .setCancellationComment(row.getString("cancel_comment"))
                .build();
    }

    private Uni<RuntimeException> clarifyException(UUID uuid) {
        Uni<Optional<Task>> taskUni = findById(uuid, SuperUser.build().getId())
                .onItem().ifNotNull().failWith(new DocumentHasNotFoundException("Task found"))
                .onItem().ifNull().failWith(new DocumentHasNotFoundException("Task not found"));

        return taskUni.onItem().transform(task -> {
            if (task.isPresent()) {
                return new RuntimeException("Task not found");
            } else {
                return new RuntimeException("Task found");
            }
        });
    }

    public Uni<UUID> insert(Task doc, Long user) {
        LocalDateTime nowTime = ZonedDateTime.now().toLocalDateTime();
        String sql = String.format("INSERT INTO %s" +
                "(reg_date, author, last_mod_date, last_mod_user, assignee, body, target_date, priority, start_date, status, title, parent_id, project_id, task_type_id, reg_number, status_date, cancel_comment)" +
                "VALUES($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13, $14, $15, $16, $17) RETURNING id;", ENTITY_DATA.tableName());
        Tuple params = Tuple.of(nowTime, user, nowTime, user);
        Tuple allParams = params
                .addLong(doc.getAssignee())
                .addString(doc.getBody());
        if (doc.getTargetDate() != null) {
            allParams.addLocalDateTime(doc.getTargetDate().toLocalDateTime());
        } else {
            allParams.addLocalDateTime(null);
        }
        allParams = params
                .addInteger(doc.getPriority())
                .addLocalDateTime(doc.getStartDate().toLocalDateTime())
                .addInteger(doc.getStatus())
                .addString(doc.getTitle())
                .addUUID(doc.getParent())
                .addUUID(doc.getProject())
                .addUUID(doc.getTaskType())
                .addString(doc.getRegNumber())
                .addLocalDateTime(doc.getStartDate().toLocalDateTime())
                .addString(doc.getCancellationComment());
        String readersSql = String.format("INSERT INTO %s(reader, entity_id, can_edit, can_delete) VALUES($1, $2, $3, $4)", ENTITY_DATA.rlsName());
        String labelsSql = "INSERT INTO prj__task_labels(task_id, label_id) VALUES($1, $2)";
        Tuple finalAllParams = allParams;
        return client.withTransaction(tx -> {
            return tx.preparedQuery(sql)
                    .execute(finalAllParams)
                    .onItem().transform(result -> result.iterator().next().getUUID("id"))
                    .onFailure().recoverWithUni(throwable -> {
                        LOGGER.error(throwable.getMessage(), throwable);
                        return Uni.createFrom().failure(new RuntimeException(String.format("Failed to insert to %s ", TASK), throwable));
                    })
                    .onItem().transformToUni(id -> {
                        return tx.preparedQuery(readersSql)
                                .execute(Tuple.of(user, id, 1, 1))
                                .onItem().ignore().andContinueWithNull()
                                .onFailure().recoverWithUni(throwable -> {
                                    LOGGER.error(throwable.getMessage());
                                    return Uni.createFrom().failure(new RuntimeException(String.format("Failed to add %s ", ENTITY_DATA.rlsName()), throwable));
                                })
                                .onItem().transform(unused -> id);
                    })
                    .onItem().transformToUni(id -> {
                        if (doc.getLabels().isEmpty()) {
                            return Uni.createFrom().item(id);
                        }
                        List<Uni<UUID>> unis = new ArrayList<>();
                        for (UUID label : doc.getLabels()) {
                            Uni<UUID> uni = tx.preparedQuery(labelsSql)
                                    .execute(Tuple.of(id, label))
                                    .onItem().ignore().andContinueWithNull()
                                    .onFailure().recoverWithUni(throwable -> {
                                        LOGGER.error(throwable.getMessage());
                                        return Uni.createFrom().failure(new RuntimeException("Failed to add Labels", throwable));
                                    })
                                    .onItem().transform(unused -> label);
                            unis.add(uni);
                        }
                        return Uni.combine().all().unis(unis).combinedWith(l -> id);
                    });
        });
    }

    public Uni<Integer> update(Task doc, Long user) throws DocumentModificationAccessException {
        UUID docId = doc.getId();
        if (1 == rlsRepository.findById(ENTITY_DATA.tableName(), user, docId)[0]) {
            LocalDateTime nowTime = ZonedDateTime.now().toLocalDateTime();
            String sql = String.format("UPDATE %s SET assignee=$1, body=$2, target_date=$3, priority=$4, " +
                    "start_date=$5, status=$6, title=$7, parent_id=$8, project_id=$9, task_type_id=$10, " +
                    "reg_number=$11, status_date=$12, cancel_comment=$13, last_mod_date=$14, last_mod_user=$15" +
                    "WHERE id=$16;", ENTITY_DATA.tableName());
            Tuple params = Tuple.of(doc.getAssignee(), doc.getBody());
            if (doc.getTargetDate() != null) {
                params.addLocalDateTime(doc.getTargetDate().toLocalDateTime());
            } else {
                params.addLocalDateTime(null);
            }
            Tuple allParams = params
                    .addInteger(doc.getPriority())
                    .addLocalDateTime(doc.getStartDate().toLocalDateTime())
                    .addInteger(doc.getStatus())
                    .addString(doc.getTitle())
                    .addUUID(doc.getParent())
                    .addUUID(doc.getProject())
                    .addUUID(doc.getTaskType())
                    .addString(doc.getRegNumber())
                    .addLocalDateTime(doc.getStartDate().toLocalDateTime())
                    .addString(doc.getCancellationComment())
                    .addLocalDateTime(nowTime)
                    .addLong(user);

            return client.withTransaction(tx -> tx.preparedQuery(sql)
                    .execute(allParams)
                    .onItem().transform(result -> result.rowCount() > 0 ? 1 : 0)
                    .onFailure().recoverWithUni(throwable -> {
                        LOGGER.error(throwable.getMessage());
                        return Uni.createFrom().item(0);
                    }));
        } else {
            throw new DocumentModificationAccessException(docId);
        }
    }

    public Uni<Void> delete(UUID uuid, Long user) {
        return delete(uuid, ENTITY_DATA.tableName());
    }
}
