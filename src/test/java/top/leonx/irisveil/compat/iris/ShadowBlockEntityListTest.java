package top.leonx.irisveil.compat.iris;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class ShadowBlockEntityListTest {
    @Test
    void supplementsExistingShadowEntitiesWithoutRepeatingLocalOrGlobalEntries() {
        Object existing = new Object();
        Object groundSpring = new Object();
        Object globalEntity = new Object();
        List<Object> shadowEntities = new ArrayList<>(List.of(existing));

        ShadowBlockEntityList.appendMissing(shadowEntities, consumer -> {
            consumer.accept(existing);
            consumer.accept(groundSpring);
            consumer.accept(globalEntity);
            consumer.accept(groundSpring);
            consumer.accept(globalEntity);
        });

        assertEquals(List.of(existing, groundSpring, globalEntity), shadowEntities);
        ShadowBlockEntityList.appendMissing(shadowEntities, consumer -> consumer.accept(groundSpring));
        assertEquals(List.of(existing, groundSpring, globalEntity), shadowEntities);
    }

    @Test
    void distinctEntityInstancesAreNotCollapsedByValueEquality() {
        record Entity(String state) { }
        Entity existing = new Entity("spring");
        Entity otherSpring = new Entity("spring");
        List<Entity> shadowEntities = new ArrayList<>(List.of(existing));

        ShadowBlockEntityList.appendMissing(shadowEntities, consumer -> consumer.accept(otherSpring));

        assertEquals(2, shadowEntities.size());
        assertSame(existing, shadowEntities.get(0));
        assertSame(otherSpring, shadowEntities.get(1));
    }
}
