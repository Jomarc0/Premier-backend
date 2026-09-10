package com.premier.device.repository;
import com.premier.device.model.GpsObservation;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface GpsObservationRepository extends JpaRepository<GpsObservation, Long> {
    List<GpsObservation> findTop100ByDeviceIdOrderByReceivedAtDesc(String deviceId);
}
