package net.mca.entity.ai.relationship;

import net.mca.entity.EntityWrapper;

public interface CompassionateEntity<T extends EntityRelationship> extends EntityWrapper {
    T getRelationships();
}
