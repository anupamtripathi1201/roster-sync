package com.moveinsync.rostersync.core;

import java.util.EnumSet;
import java.util.Set;

//We need to store the attributes of a user with exactly one owner

//HR-> Name,Email,Phone,Manager_id,office_code,shift_code

//Transport admin -> pickup point and special assistance

public enum Field{
    NAME(Owner.HR),
    EMAIL(Owner.HR),
    PHONE(Owner.HR),
    MANAGER_ID(Owner.HR),
    OFFICE_CODE(Owner.HR),
    SHIFT_CODE(Owner.HR),
    PICKUP_POINT(Owner.TRANSPORT_ADMIN),
    SPECIAL_ASSISTANCE(Owner.TRANSPORT_ADMIN);

    public enum Owner {HR, TRANSPORT_ADMIN}
    
    private final Owner owner;

    Field(Owner owner){
        this.owner = owner;
    }

    public boolean isOwnedBy(Owner candidate){
        return owner==candidate;
    }


 public static Set<Field> ownedBy(Owner owner) {
        EnumSet<Field> result = EnumSet.noneOf(Field.class);
        for (Field f : values()) {
            if (f.owner == owner) {
                result.add(f);
            }
        }
        return result;
    }


}