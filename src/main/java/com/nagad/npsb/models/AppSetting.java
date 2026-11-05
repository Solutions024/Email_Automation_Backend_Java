package com.nagad.npsb.models;

import com.nagad.npsb.Py;
import com.nagad.npsb.db.Col;
import com.nagad.npsb.db.Deferred;
import com.nagad.npsb.db.Entity;
import com.nagad.npsb.db.Id;
import com.nagad.npsb.db.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** One setting changed in the app (Email Configuration, System Settings, alert times). Missing rows use the .env default. */
@Table("app_settings")
public class AppSetting extends Entity {
    @Id(auto = false) public String key;
    public String value;  // JSON
    public String updatedBy;
    public LocalDateTime updatedAt = Py.now();

    @Override
    protected void beforeUpdate() {
        updatedAt = Py.now();
    }
}
