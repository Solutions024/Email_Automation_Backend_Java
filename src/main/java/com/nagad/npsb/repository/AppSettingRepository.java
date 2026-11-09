package com.nagad.npsb.repository;

import com.nagad.npsb.entity.AppSetting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppSettingRepository extends JpaRepository<AppSetting, String> {
}
