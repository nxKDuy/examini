package com.datn.organization.controller;

import com.datn.organization.domain.Dto.*;
import com.datn.organization.security.Actor;
import com.datn.organization.security.RequestAuthentication;
import com.datn.organization.service.OrganizationService;
import com.datn.organization.service.Paging;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

/** Public API (verified Auth JWT). Route id typed UUID, so a literal "internal" segment can never bind here. */
@RestController
@RequestMapping("/organizations")
public class OrganizationController {
    private final OrganizationService service;

    public OrganizationController(OrganizationService service) { this.service = service; }

    private static Actor actor(HttpServletRequest request) { return (Actor) request.getAttribute(RequestAuthentication.ACTOR); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrganizationView create(HttpServletRequest r, @Valid @RequestBody CreateOrganization body) { return service.create(actor(r), body); }

    @GetMapping
    public PageView<OrganizationView> mine(HttpServletRequest r, @RequestParam(required = false) String status, @RequestParam(required = false) String q,
                                           @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return service.mine(actor(r), status, q, Paging.of(page, size));
    }

    @GetMapping("/{id}")
    public OrganizationView get(HttpServletRequest r, @PathVariable UUID id) { return service.get(actor(r), id); }

    @PatchMapping("/{id}")
    public OrganizationView update(HttpServletRequest r, @PathVariable UUID id, @Valid @RequestBody UpdateOrganization body) { return service.update(actor(r), id, body); }

    @PostMapping("/{id}/archive")
    public OrganizationView archive(HttpServletRequest r, @PathVariable UUID id, @Valid @RequestBody Revision body) { return service.archive(actor(r), id, body); }

    @GetMapping("/{id}/capabilities")
    public Access capabilities(HttpServletRequest r, @PathVariable UUID id) { return service.capabilities(actor(r), id); }

    @PostMapping("/{id}/transfer-ownership")
    public OrganizationView transfer(HttpServletRequest r, @PathVariable UUID id, @Valid @RequestBody TransferOwnership body) { return service.transferOwnership(actor(r), id, body); }

    @PostMapping("/{id}/leave")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void leave(HttpServletRequest r, @PathVariable UUID id) { service.leave(actor(r), id); }

    @GetMapping("/{id}/members")
    public PageView<MemberView> members(HttpServletRequest r, @PathVariable UUID id, @RequestParam(required = false) String status,
                                        @RequestParam(required = false) String role, @RequestParam(required = false) Integer page,
                                        @RequestParam(required = false) Integer size) {
        return service.members(actor(r), id, status, role, Paging.of(page, size));
    }

    @PatchMapping("/{id}/members/{membershipId}")
    public MemberView updateMember(HttpServletRequest r, @PathVariable UUID id, @PathVariable UUID membershipId, @Valid @RequestBody UpdateMember body) {
        return service.updateMember(actor(r), id, membershipId, body);
    }

    @DeleteMapping("/{id}/members/{membershipId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeMember(HttpServletRequest r, @PathVariable UUID id, @PathVariable UUID membershipId, @RequestParam long revision) {
        service.removeMember(actor(r), id, membershipId, revision);
    }

    @GetMapping("/{id}/audit")
    public PageView<AuditView> audit(HttpServletRequest r, @PathVariable UUID id, @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return service.audit(actor(r), id, Paging.of(page, size));
    }

    // ---- invitations (organization side)
    @PostMapping("/{id}/invitations")
    public ResponseEntity<InvitationView> invite(HttpServletRequest r, @PathVariable UUID id, @Valid @RequestBody CreateInvitation body) {
        var result = service.invite(actor(r), id, body);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.invitation());
    }

    @GetMapping("/{id}/invitations")
    public PageView<InvitationView> invitations(HttpServletRequest r, @PathVariable UUID id, @RequestParam(required = false) String status,
                                                @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return service.invitations(actor(r), id, status, Paging.of(page, size));
    }

    @PostMapping("/{id}/invitations/{invitationId}/revoke")
    public InvitationView revoke(HttpServletRequest r, @PathVariable UUID id, @PathVariable UUID invitationId) { return service.revoke(actor(r), id, invitationId); }

    // ---- invitations (recipient side)
    @GetMapping("/invitations/mine")
    public PageView<InvitationView> myInvitations(HttpServletRequest r, @RequestParam(required = false) String status,
                                                  @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return service.myInvitations(actor(r), status, Paging.of(page, size));
    }

    @PostMapping("/invitations/{invitationId}/accept")
    public MemberView accept(HttpServletRequest r, @PathVariable UUID invitationId) { return service.accept(actor(r), invitationId); }

    @PostMapping("/invitations/{invitationId}/decline")
    public InvitationView decline(HttpServletRequest r, @PathVariable UUID invitationId) { return service.decline(actor(r), invitationId); }
}
