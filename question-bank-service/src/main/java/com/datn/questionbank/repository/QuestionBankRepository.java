package com.datn.questionbank.repository;
import com.datn.questionbank.entity.QuestionBank;
import org.springframework.data.jpa.repository.*;
import java.util.*;
public interface QuestionBankRepository extends JpaRepository<QuestionBank,UUID>, JpaSpecificationExecutor<QuestionBank> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select b from QuestionBank b where b.id=:id")
    Optional<QuestionBank> findForUpdate(UUID id);

    Optional<QuestionBank> findByOwnerUserIdAndDefaultBankTrue(UUID owner);
    Optional<QuestionBank> findByTypeAndDefaultBankTrue(QuestionBank.BankType type);
    @Modifying @Query(value="INSERT INTO question_banks(id,type,owner_user_id,name,status,is_default,created_at) VALUES(gen_random_uuid(),'PERSONAL',:owner,'Personal Bank','ACTIVE',true,CURRENT_TIMESTAMP) ON CONFLICT (owner_user_id) WHERE type='PERSONAL' AND is_default DO NOTHING",nativeQuery=true)
    void ensurePersonal(UUID owner);
}
