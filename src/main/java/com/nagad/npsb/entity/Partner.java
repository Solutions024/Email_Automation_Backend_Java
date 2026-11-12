package com.nagad.npsb.entity;

import jakarta.persistence.Entity;
import org.hibernate.annotations.DynamicUpdate;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A Bank / MFS / PSP that receives our emails. Known by its NPSB_ORG_CODE (orgCode: unique, stored without leading
 * zeros, see OrgCodes): file rows belong to the partner with their code. The name is shown and used in the emails
 * (names are unique too). id is only the link between the tables.
 */
@Getter
@Setter
@Entity
@DynamicUpdate  // UPDATE only the changed columns (like the Python backend), never stale values of others
@Table(name = "partners")
public class Partner {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;
    private String orgCode;  // NPSB_ORG_CODE, e.g. "103" (null: an older partner whose code is not known yet)
    private String name;
    private Boolean isActive = true;
}
