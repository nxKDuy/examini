package com.datn.questionbank.service;
import com.datn.questionbank.entity.*;
import com.datn.questionbank.repository.QuestionBankRepository;
import com.datn.questionbank.security.QuestionActor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.UUID;
@Service @RequiredArgsConstructor
public class BankRegistry {
    private final QuestionBankRepository banks;
    private final OrganizationAuthorizationClient organizationAuthorization;
    @Transactional public UUID defaultPersonal(UUID owner) {
        banks.ensurePersonal(owner);
        return banks.findByOwnerUserIdAndDefaultBankTrue(owner).orElseThrow().getId();
    }
    public QuestionBank get(UUID id) { return banks.findById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Không tìm thấy bank")); }
    public void requireWrite(QuestionBank bank,QuestionActor actor) {
        if(bank.getType()==QuestionBank.BankType.ORGANIZATION) {
            organizationAuthorization.require(bank.getOrganizationId(), actor.userId(),
                    OrganizationAuthorizationClient.Capability.BANK_MANAGE);
            return;
        }
        if(bank.getType()==QuestionBank.BankType.PERSONAL && actor.author() && actor.userId().equals(bank.getOwnerUserId())) return;
        if(bank.getType()==QuestionBank.BankType.SYSTEM && actor.systemAdmin()) return;
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Không có quyền ghi bank");
    }
    public void requireRead(QuestionBank bank,QuestionActor actor) {
        if(bank.getType()==QuestionBank.BankType.SYSTEM || bank.getType()==QuestionBank.BankType.PUBLIC) return;
        if(bank.getType()==QuestionBank.BankType.PERSONAL && actor.userId().equals(bank.getOwnerUserId())) return;
        if(bank.getType()==QuestionBank.BankType.ORGANIZATION) {
            organizationAuthorization.require(bank.getOrganizationId(), actor.userId(),
                    OrganizationAuthorizationClient.Capability.BANK_READ);
            return;
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Không tìm thấy bank");
    }
    public void requireUse(UUID bank,QuestionActor actor) {
        var organization = get(bank).getOrganizationId();
        organizationAuthorization.require(organization, actor.userId(), OrganizationAuthorizationClient.Capability.BANK_USE);
    }
    public void requireReview(UUID bank,QuestionActor actor) {
        if (actor.internal()) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Server identity không review contribution");
        var organization = get(bank).getOrganizationId();
        organizationAuthorization.require(organization, actor.userId(), OrganizationAuthorizationClient.Capability.BANK_REVIEW);
    }
    public void requireSubmit(UUID bank,QuestionActor actor) {
        var organization = get(bank).getOrganizationId();
        organizationAuthorization.require(organization, actor.userId(), OrganizationAuthorizationClient.Capability.BANK_SUBMIT);
    }
    public void requireOrganizationManage(UUID organization, QuestionActor actor) {
        organizationAuthorization.require(organization, actor.userId(), OrganizationAuthorizationClient.Capability.BANK_MANAGE);
    }
}
