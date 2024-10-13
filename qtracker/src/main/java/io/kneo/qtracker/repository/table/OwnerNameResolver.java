package io.kneo.qtracker.repository.table;

import io.kneo.core.repository.table.EntityData;
import io.kneo.core.repository.table.TableNameResolver;

public class OwnerNameResolver extends TableNameResolver {
    public static final String OWNERS = "owners";
    public static final String VEHICLES = "vehicles";
    public static final String CONSUMINGS = "consumings";

    private static final String OWNERS_TABLE_NAME = "__owners";
    private static final String OWNERS_ACCESS_TABLE_NAME = "owners__readers";
    private static final String VEHICLES_TABLE_NAME = "__vehicles";
    private static final String VEHICLES_ACCESS_TABLE_NAME = "vehicles__readers";
    private static final String CONSUMINGS_TABLE_NAME = "__consumings";
    private static final String CONSUMINGS_ACCESS_TABLE_NAME = "consumings__readers";

    public EntityData getEntityNames(String type) {
        return switch (type) {
            case OWNERS -> new EntityData(OWNERS_TABLE_NAME, OWNERS_ACCESS_TABLE_NAME);
            case VEHICLES -> new EntityData(VEHICLES_TABLE_NAME, VEHICLES_ACCESS_TABLE_NAME);
            case CONSUMINGS -> new EntityData(CONSUMINGS_TABLE_NAME, CONSUMINGS_ACCESS_TABLE_NAME);
            default -> super.getEntityNames(type);
        };
    }

    public static OwnerNameResolver create() {
        return new OwnerNameResolver();
    }

}
