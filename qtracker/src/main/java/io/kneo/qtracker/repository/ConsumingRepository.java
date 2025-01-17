package io.kneo.qtracker.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.kneo.core.model.user.IUser;
import io.kneo.core.repository.AsyncRepository;
import io.kneo.core.repository.rls.RLSRepository;
import io.kneo.core.repository.table.EntityData;
import io.kneo.qtracker.model.Consuming;
import io.kneo.qtracker.model.Image;
import io.kneo.qtracker.repository.table.QTrackerNameResolver;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Row;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

@ApplicationScoped
public class ConsumingRepository extends AsyncRepository {
    private static final EntityData entityData = QTrackerNameResolver.create().getEntityNames(QTrackerNameResolver.CONSUMINGS);

    @Inject
    public ConsumingRepository(PgPool client, ObjectMapper mapper, RLSRepository rlsRepository) {
        super(client, mapper, rlsRepository);
    }

    public Uni<List<Consuming>> getAll(final int limit, final int offset, final IUser user) {
        String sql = "SELECT * FROM " + entityData.getTableName() + " v, " + entityData.getRlsName() + " vr WHERE v.id = vr.entity_id AND vr.reader = $1";
        if (limit > 0) {
            sql += String.format(" LIMIT %s OFFSET %s", limit, offset);
        }
        return client.preparedQuery(sql)
                .execute(Tuple.of(user.getId()))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(this::from)
                .collect().asList();
    }

    public Uni<Integer> getAllCount(IUser user) {
        return getAllCount(user.getId(), entityData.getTableName(), entityData.getRlsName());
    }

    public Uni<List<Consuming>> getAllMine(final int limit, final int offset, final String telegramName, final IUser user) {
        String sql = """
        SELECT c.* 
        FROM %s c
        JOIN qtracker__vehicles v ON c.vehicle_id = v.id
        JOIN qtracker__owners o ON v.owner_id = o.id
        JOIN %s vr ON c.id = vr.entity_id
        WHERE o.telegram_name = $1 AND vr.reader = $2
    """.formatted(entityData.getTableName(), entityData.getRlsName());

        if (limit > 0) {
            sql += String.format(" LIMIT %s OFFSET %s", limit, offset);
        }

        return client.preparedQuery(sql)
                .execute(Tuple.of(telegramName, user.getId()))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(this::from)
                .collect().asList();
    }



    public Uni<List<Consuming>> getLastTwo(UUID vehicleId,  final IUser user) {
        String sql = "SELECT * FROM " + entityData.getTableName() + " v, " + entityData.getRlsName() + " vr " +
                "WHERE v.id = vr.entity_id AND vr.reader = $1 AND v.vehicle_id=$2 ORDER BY v.reg_date DESC";
        sql += String.format(" LIMIT %s", 2);

        return client.preparedQuery(sql)
                .execute(Tuple.of(user.getId(), vehicleId))
                .onItem().transformToMulti(rows -> Multi.createFrom().iterable(rows))
                .onItem().transform(this::from)
                .collect().asList();
    }


    public Uni<Consuming> findById(UUID id) {
        String sql = "SELECT * FROM " + entityData.getTableName() + " WHERE id = $1";
        return client.preparedQuery(sql)
                .execute(Tuple.of(id))
                .onItem().transform(rows -> rows.iterator().hasNext() ? from(rows.iterator().next()) : null);
    }

    public Uni<Consuming> insert(Consuming consuming, IUser user, List<Image> images) {
        LocalDateTime nowTime = ZonedDateTime.now().toLocalDateTime();
        String sql = String.format("INSERT INTO %s " +
                "(reg_date, author, last_mod_date, last_mod_user, vehicle_id, status, total_km, last_liters, last_cost, event_date, add_info) " +
                "VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11) RETURNING id;", entityData.getTableName());

        JsonObject addInfoJson = new JsonObject(consuming.getAddInfo());
        Tuple params = Tuple.tuple();
        params.addValue(nowTime)
                .addValue(user.getId())
                .addValue(nowTime)
                .addValue(user.getId())
                .addUUID(consuming.getVehicleId())
                .addInteger(consuming.getStatus())
                .addDouble(consuming.getTotalKm())
                .addDouble(consuming.getLastLiters())
                .addDouble(consuming.getLastCost())
                .addValue(nowTime)
                .addJsonObject(addInfoJson);

        String readersSql = String.format("INSERT INTO %s(reader, entity_id, can_edit, can_delete) VALUES($1, $2, $3, $4)", entityData.getRlsName());

        return client.withTransaction(tx -> {
            return tx.preparedQuery(sql)
                    .execute(params)
                    .onItem().transform(result -> result.iterator().next().getUUID("id"))
                    .onFailure().recoverWithUni(t -> Uni.createFrom().failure(t))
                    .onItem().transformToUni(id -> {
                        return tx.preparedQuery(readersSql)
                                .execute(Tuple.of(user.getId(), id, true, true))
                                .onItem().ignore().andContinueWithNull()
                                .onFailure().recoverWithUni(t -> Uni.createFrom().failure(t))
                                .onItem().transformToUni(unused -> {
                                    if (images != null && !images.isEmpty()) {
                                        String imageSql = String.format("INSERT INTO %s (consuming_id, image_data, type, confidence, add_info, description, num_of_seq) " +
                                                "VALUES ($1, $2, $3, $4, $5, $6, $7)", entityData.getFilesTableName());
                                        Uni<Void> imagesInsertion = Uni.combine().all().unis(
                                                images.stream().map(image -> {
                                                    JsonObject imageAddInfoJson = new JsonObject(image.getAddInfo());
                                                    Tuple imageParams = Tuple.of(
                                                            id,
                                                            image.getImageData(),
                                                            image.getType(),
                                                            image.getConfidence(),
                                                            imageAddInfoJson,
                                                            image.getDescription()
                                                    );
                                                    imageParams.addInteger(image.getNumOfSeq());
                                                    return tx.preparedQuery(imageSql).execute(imageParams).onItem().ignore().andContinueWithNull();
                                                }).toList()
                                        ).with(unusedImages -> null);

                                        return imagesInsertion.onItem().transform(unusedImages -> id);
                                    }
                                    return Uni.createFrom().item(id);
                                });
                    });
        }).onItem().transformToUni(this::findById);
    }


    public Uni<Consuming> update(UUID id, Consuming consuming, IUser user) {
        LocalDateTime nowTime = ZonedDateTime.now().toLocalDateTime();
        String sql = String.format("UPDATE %s SET last_mod_user = $1, last_mod_date = $2, total_km = $3, last_liters = $4, last_cost = $5 WHERE id = $6;", entityData.getTableName());
        Tuple params = Tuple.tuple();
        params.addValue(user.getId())
                .addValue(nowTime)
                .addDouble(consuming.getTotalKm())
                .addDouble(consuming.getLastLiters())
                .addDouble(consuming.getLastCost())
                .addUUID(id);

        return client.preparedQuery(sql)
                .execute(params)
                .onItem().transformToUni(updated -> findById(id));
    }

    public Uni<Integer> delete(UUID uuid, IUser user) {
        return delete(uuid, entityData, user);
    }

    private Consuming from(Row row) {
        Consuming doc = new Consuming();
        setDefaultFields(doc, row);
        doc.setId(row.getUUID("id"));
        doc.setVehicleId(row.getUUID("vehicle_id"));
        doc.setTotalKm(row.getDouble("total_km"));
        doc.setLastLiters(row.getDouble("last_liters"));
        doc.setLastCost(row.getDouble("last_cost"));
        return doc;
    }
}
