package com.premier.payment.repository;
import com.premier.payment.model.ProviderEvent;
import org.springframework.data.jpa.repository.JpaRepository;
public interface ProviderEventRepository extends JpaRepository<ProviderEvent, String> {}
