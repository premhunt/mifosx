/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.mifosplatform.portfolio.group.service;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang.StringUtils;
import org.joda.time.LocalDate;
import org.mifosplatform.infrastructure.core.api.JsonCommand;
import org.mifosplatform.infrastructure.core.data.CommandProcessingResult;
import org.mifosplatform.infrastructure.core.data.CommandProcessingResultBuilder;
import org.mifosplatform.infrastructure.core.exception.PlatformDataIntegrityException;
import org.mifosplatform.infrastructure.security.service.PlatformSecurityContext;
import org.mifosplatform.organisation.office.domain.Office;
import org.mifosplatform.organisation.office.domain.OfficeRepository;
import org.mifosplatform.organisation.office.exception.InvalidOfficeException;
import org.mifosplatform.organisation.office.exception.OfficeNotFoundException;
import org.mifosplatform.organisation.staff.domain.Staff;
import org.mifosplatform.organisation.staff.domain.StaffRepository;
import org.mifosplatform.organisation.staff.exception.StaffNotFoundException;
import org.mifosplatform.portfolio.client.domain.Client;
import org.mifosplatform.portfolio.client.domain.ClientRepository;
import org.mifosplatform.portfolio.client.exception.ClientNotFoundException;
import org.mifosplatform.portfolio.group.api.GroupingTypesApiConstants;
import org.mifosplatform.portfolio.group.domain.Group;
import org.mifosplatform.portfolio.group.domain.GroupLevel;
import org.mifosplatform.portfolio.group.domain.GroupLevelRepository;
import org.mifosplatform.portfolio.group.domain.GroupRepository;
import org.mifosplatform.portfolio.group.domain.GroupTypes;
import org.mifosplatform.portfolio.group.exception.GroupHasNoStaffException;
import org.mifosplatform.portfolio.group.exception.GroupMustBePendingToBeDeletedException;
import org.mifosplatform.portfolio.group.exception.GroupNotFoundException;
import org.mifosplatform.portfolio.group.exception.InvalidGroupLevelException;
import org.mifosplatform.portfolio.group.serialization.GroupingTypesDataValidator;
import org.mifosplatform.portfolio.note.domain.Note;
import org.mifosplatform.portfolio.note.domain.NoteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ObjectUtils;

import com.google.common.collect.Sets;

@Service
public class GroupingTypesWritePlatformServiceJpaRepositoryImpl implements GroupingTypesWritePlatformService {

    private final static Logger logger = LoggerFactory.getLogger(GroupingTypesWritePlatformServiceJpaRepositoryImpl.class);

    private final PlatformSecurityContext context;
    private final GroupRepository groupRepository;
    private final ClientRepository clientRepository;
    private final OfficeRepository officeRepository;
    private final StaffRepository staffRepository;
    private final NoteRepository noteRepository;
    private final GroupLevelRepository groupLevelRepository;
    private final GroupingTypesDataValidator fromApiJsonDeserializer;

    @Autowired
    public GroupingTypesWritePlatformServiceJpaRepositoryImpl(final PlatformSecurityContext context, final GroupRepository groupRepository,
            final ClientRepository clientRepository, final OfficeRepository officeRepository, final StaffRepository staffRepository,
            final NoteRepository noteRepository,
            final GroupLevelRepository groupLevelRepository, final GroupingTypesDataValidator fromApiJsonDeserializer) {
        this.context = context;
        this.groupRepository = groupRepository;
        this.clientRepository = clientRepository;
        this.officeRepository = officeRepository;
        this.staffRepository = staffRepository;
        this.noteRepository = noteRepository;
        this.groupLevelRepository = groupLevelRepository;
        this.fromApiJsonDeserializer = fromApiJsonDeserializer;
    }

    private CommandProcessingResult createGroupingType(final JsonCommand command, final GroupTypes groupingType, final Long centerId) {
        try {
            final String name = command.stringValueOfParameterNamed(GroupingTypesApiConstants.nameParamName);
            final String externalId = command.stringValueOfParameterNamed(GroupingTypesApiConstants.externalIdParamName);

            Long officeId = null;
            Group parentGroup = null;

            if (centerId == null) {
                officeId = command.longValueOfParameterNamed(GroupingTypesApiConstants.officeIdParamName);
            } else {
                parentGroup = this.groupRepository.findOne(centerId);
                if (parentGroup == null) { throw new GroupNotFoundException(centerId); }
                officeId = parentGroup.getOfficeId();
            }

            final Office groupOffice = this.officeRepository.findOne(officeId);
            if (groupOffice == null) { throw new OfficeNotFoundException(officeId); }

            Staff staff = null;
            final Long staffId = command.longValueOfParameterNamed(GroupingTypesApiConstants.staffIdParamName);
            if (staffId != null) {
                staff = this.staffRepository.findByOffice(staffId, officeId);
                if (staff == null) { throw new StaffNotFoundException(staffId); }
            }

            final Set<Client> clientMembers = assembleSetOfClients(officeId, command);

            final Set<Group> groupMembers = assembleSetOfChildGroups(officeId, command);

            final boolean active = command.booleanPrimitiveValueOfParameterNamed(GroupingTypesApiConstants.activeParamName);
            final LocalDate activationDate = command.localDateValueOfParameterNamed(GroupingTypesApiConstants.activationDateParamName);
            GroupLevel groupLevel = this.groupLevelRepository.findOne(groupingType.getId());
            final Group newGroup = Group.newGroup(groupOffice, staff, parentGroup, groupLevel, name, externalId, active, activationDate,
                    clientMembers, groupMembers);

            // pre-save to generate id for use in group hierarchy
            this.groupRepository.save(newGroup);

            newGroup.generateHierarchy();

            this.groupRepository.saveAndFlush(newGroup);

            return new CommandProcessingResultBuilder() //
                    .withCommandId(command.commandId()) //
                    .withOfficeId(groupOffice.getId()) //
                    .withGroupId(newGroup.getId()) //
                    .withEntityId(newGroup.getId()) //
                    .build();

        } catch (final DataIntegrityViolationException dve) {
            handleGroupDataIntegrityIssues(command, dve, groupingType);
            return CommandProcessingResult.empty();
        }
    }

    @Transactional
    @Override
    public CommandProcessingResult createCenter(final JsonCommand command) {

        this.fromApiJsonDeserializer.validateForCreateCenter(command);

        final Long centerId = null;
        return createGroupingType(command, GroupTypes.CENTER, centerId);
    }

    @Transactional
    @Override
    public CommandProcessingResult createGroup(final Long centerId, final JsonCommand command) {

        if (centerId != null) {
            this.fromApiJsonDeserializer.validateForCreateCenterGroup(command);
        } else {
            this.fromApiJsonDeserializer.validateForCreateGroup(command);
        }

        return createGroupingType(command, GroupTypes.GROUP, centerId);
    }

    @Transactional
    @Override
    public CommandProcessingResult updateCenter(final Long centerId, final JsonCommand command) {

        this.fromApiJsonDeserializer.validateForUpdateCenter(command);

        return updateGroupingType(centerId, command, GroupTypes.CENTER);
    }

    @Transactional
    @Override
    public CommandProcessingResult updateGroup(final Long groupId, final JsonCommand command) {

        this.fromApiJsonDeserializer.validateForUpdateGroup(command);

        return updateGroupingType(groupId, command, GroupTypes.GROUP);
    }

    private CommandProcessingResult updateGroupingType(final Long groupId, final JsonCommand command, final GroupTypes groupingType) {

        try {
            final Map<String, Object> actualChanges = new LinkedHashMap<String, Object>(9);

            final Group groupForUpdate = this.groupRepository.findOne(groupId);
            if (groupForUpdate == null) { throw new GroupNotFoundException(groupId); }

            final Long officeId = groupForUpdate.getOfficeId();

            if (command.isChangeInStringParameterNamed(GroupingTypesApiConstants.nameParamName, groupForUpdate.getName())) {
                final String newValue = command.stringValueOfParameterNamed(GroupingTypesApiConstants.nameParamName);
                actualChanges.put(GroupingTypesApiConstants.nameParamName, newValue);
                groupForUpdate.setName(StringUtils.defaultIfEmpty(newValue, null));
            }

            if (command.isChangeInStringParameterNamed(GroupingTypesApiConstants.externalIdParamName, groupForUpdate.getExternalId())) {
                final String newValue = command.stringValueOfParameterNamed(GroupingTypesApiConstants.externalIdParamName);
                actualChanges.put(GroupingTypesApiConstants.externalIdParamName, newValue);
                groupForUpdate.setExternalId(StringUtils.defaultIfEmpty(newValue, null));
            }

            final Staff presentStaff = groupForUpdate.getStaff();
            Long presentStaffId = null;
            if (presentStaff != null) {
                presentStaffId = presentStaff.getId();
            }

            if (command.isChangeInLongParameterNamed(GroupingTypesApiConstants.staffIdParamName, presentStaffId)) {
                final Long newValue = command.longValueOfParameterNamed(GroupingTypesApiConstants.staffIdParamName);
                actualChanges.put(GroupingTypesApiConstants.staffIdParamName, newValue);

                Staff newStaff = null;
                if (newValue != null) {
                    newStaff = this.staffRepository.findByOffice(newValue, officeId);
                    if (newStaff == null) { throw new StaffNotFoundException(newValue); }
                }
                groupForUpdate.setStaff(newStaff);
            }

            GroupLevel groupLevel = this.groupLevelRepository.findOne(groupForUpdate.getGroupLevel().getId());

            /*
             * Ignoring parentId param, if group for update is super parent.
             * TODO Need to check: Ignoring is correct or need throw unsupported
             * param
             */
            if (!groupLevel.isSuperParent()) {

                Long parentId = null;
                final Group presentParentGroup = groupForUpdate.getParent();

                if (presentParentGroup != null) {
                    parentId = presentParentGroup.getId();
                }

                if (command.isChangeInLongParameterNamed(GroupingTypesApiConstants.centerIdParamName, parentId)) {

                    final Long newValue = command.longValueOfParameterNamed(GroupingTypesApiConstants.centerIdParamName);
                    actualChanges.put(GroupingTypesApiConstants.centerIdParamName, newValue);
                    Group newParentGroup = null;
                    if (newValue != null) {
                        newParentGroup = this.groupRepository.findOne(newValue);
                        if (newParentGroup == null) { throw new StaffNotFoundException(newValue); }

                        if (!newParentGroup.isOfficeIdentifiedBy(officeId)) {
                            final String errorMessage = "Group and parent group must have the same office";
                            throw new InvalidOfficeException("group", "attach.to.parent.group", errorMessage);
                        }
                        /*
                         * If Group is not super parent then validate group
                         * level's parent level is same as group parent's level
                         * this check makes sure new group is added at immediate
                         * next level in hierarchy
                         */

                        if (!groupForUpdate.getGroupLevel().isIdentifiedByParentId(newParentGroup.getGroupLevel().getId())) {
                            final String errorMessage = "Parent group's level is  not equal to child level's parent level ";
                            throw new InvalidGroupLevelException("add", "invalid.level", errorMessage);
                        }
                    }

                    groupForUpdate.setParent(newParentGroup);

                    // Parent has changed, re-generate 'Hierarchy' as parent is
                    // changed
                    groupForUpdate.generateHierarchy();

                }
            }

            final Set<Client> clientMembers = assembleSetOfClients(officeId, command);

            if (!clientMembers.equals(groupForUpdate.getClientMembers())) {
                Set<Client> diffClients = Sets.symmetricDifference(clientMembers, groupForUpdate.getClientMembers());
                final String[] diffClientsIds = getClientIds(diffClients);
                actualChanges.put(GroupingTypesApiConstants.clientMembersParamName, diffClientsIds);
                groupForUpdate.setClientMembers(clientMembers);
            }

            this.groupRepository.saveAndFlush(groupForUpdate);

            return new CommandProcessingResultBuilder() //
                    .withCommandId(command.commandId()) //
                    .withOfficeId(groupForUpdate.getId()) //
                    .withGroupId(groupForUpdate.getId()) //
                    .withEntityId(groupForUpdate.getId()) //
                    .with(actualChanges) //
                    .build();

        } catch (final DataIntegrityViolationException dve) {
            handleGroupDataIntegrityIssues(command, dve, groupingType);
            return CommandProcessingResult.empty();
        }
    }

    @Transactional
    @Override
    public CommandProcessingResult unassignStaff(final Long grouptId, final JsonCommand command) {

        this.context.authenticatedUser();

        final Map<String, Object> actualChanges = new LinkedHashMap<String, Object>(9);

        this.fromApiJsonDeserializer.validateForUnassignStaff(command.json());

        final Group groupForUpdate = this.groupRepository.findOne(grouptId);
        if (groupForUpdate == null) { throw new GroupNotFoundException(grouptId); }

        final Staff presentStaff = groupForUpdate.getStaff();
        Long presentStaffId = null;
        if (presentStaff == null) { throw new GroupHasNoStaffException(grouptId); }
        presentStaffId = presentStaff.getId();
        final String staffIdParamName = "staffId";
        if (!command.isChangeInLongParameterNamed(staffIdParamName, presentStaffId)) {
            groupForUpdate.unassigStaff();
        }
        this.groupRepository.saveAndFlush(groupForUpdate);

        actualChanges.put(staffIdParamName, null);

        return new CommandProcessingResultBuilder() //
                .withCommandId(command.commandId()) //
                .withOfficeId(groupForUpdate.getId()) //
                .withGroupId(groupForUpdate.getOfficeId()) //
                .withEntityId(groupForUpdate.getId()) //
                .with(actualChanges) //
                .build();

    }

    @Transactional
    @Override
    public CommandProcessingResult deleteGroup(final Long groupId) {

        final Group groupForDelete = this.groupRepository.findOne(groupId);
        if (groupForDelete == null) { throw new GroupNotFoundException(groupId); }

        if (groupForDelete.isNotPending()) { throw new GroupMustBePendingToBeDeletedException(groupId); }

        final List<Note> relatedNotes = this.noteRepository.findByGroupId(groupId);
        this.noteRepository.deleteInBatch(relatedNotes);

        this.groupRepository.delete(groupId);

        return new CommandProcessingResultBuilder() //
                .withOfficeId(groupForDelete.getId()) //
                .withGroupId(groupForDelete.getOfficeId()) //
                .withEntityId(groupForDelete.getId()) //
                .build();
    }

    private Set<Client> assembleSetOfClients(final Long officeId, final JsonCommand command) {

        final Set<Client> clientMembers = new HashSet<Client>();
        final String[] clientMembersArray = command.arrayValueOfParameterNamed(GroupingTypesApiConstants.clientMembersParamName);

        if (!ObjectUtils.isEmpty(clientMembersArray)) {
            for (final String clientId : clientMembersArray) {
                final Long id = Long.valueOf(clientId);
                final Client client = this.clientRepository.findOne(id);
                if (client == null || client.isDeleted()) { throw new ClientNotFoundException(id); }
                if (!client.isOfficeIdentifiedBy(officeId)) {
                    final String errorMessage = "Group and Client must have the same office.";
                    throw new InvalidOfficeException("client", "attach.to.group", errorMessage);
                }
                clientMembers.add(client);
            }
        }

        return clientMembers;
    }

    private Set<Group> assembleSetOfChildGroups(final Long officeId, final JsonCommand command) {

        final Set<Group> childGroups = new HashSet<Group>();
        final String[] childGroupsArray = command.arrayValueOfParameterNamed(GroupingTypesApiConstants.groupMembersParamName);

        if (!ObjectUtils.isEmpty(childGroupsArray)) {
            for (final String groupId : childGroupsArray) {
                final Long id = Long.valueOf(groupId);
                final Group group = this.groupRepository.findOne(id);
                if (group == null) { throw new GroupNotFoundException(id); }
                if (!group.isOfficeIdentifiedBy(officeId)) {
                    final String errorMessage = "Group and child groups must have the same office.";
                    throw new InvalidOfficeException("group", "attach.to.parent.group", errorMessage);
                }
                childGroups.add(group);
            }
        }

        return childGroups;
    }

    private String[] getClientIds(final Set<Client> clients) {

        String[] clientIds = new String[clients.size()];
        Iterator<Client> it = clients.iterator();
        for (int i = 0; it.hasNext(); i++) {
            clientIds[i] = it.next().getId().toString();
        }
        return clientIds;
    }

    /*
     * Guaranteed to throw an exception no matter what the data integrity issue
     * is.
     */
    private void handleGroupDataIntegrityIssues(final JsonCommand command, final DataIntegrityViolationException dve,
            final GroupTypes groupLevel) {

        String levelName = "Invalid";
        switch (groupLevel) {
            case CENTER:
                levelName = "Center";
            break;
            case GROUP:
                levelName = "Group";
            break;
            case INVALID:
            break;
        }

        final Throwable realCause = dve.getMostSpecificCause();
        String errorMessageForUser = null;
        String errorMessageForMachine = null;

        if (realCause.getMessage().contains("external_id")) {

            final String externalId = command.stringValueOfParameterNamed(GroupingTypesApiConstants.externalIdParamName);
            errorMessageForUser = levelName + " with externalId `" + externalId + "` already exists.";
            errorMessageForMachine = "error.msg." + levelName.toLowerCase() + ".duplicate.externalId";
            throw new PlatformDataIntegrityException(errorMessageForMachine, errorMessageForUser,
                    GroupingTypesApiConstants.externalIdParamName, externalId);
        } else if (realCause.getMessage().contains("name")) {

            final String name = command.stringValueOfParameterNamed(GroupingTypesApiConstants.nameParamName);
            errorMessageForUser = levelName + " with name `" + name + "` already exists.";
            errorMessageForMachine = "error.msg." + levelName.toLowerCase() + ".duplicate.name";
            throw new PlatformDataIntegrityException(errorMessageForMachine, errorMessageForUser, GroupingTypesApiConstants.nameParamName,
                    name);
        }

        logger.error(dve.getMessage(), dve);
        throw new PlatformDataIntegrityException("error.msg.group.unknown.data.integrity.issue",
                "Unknown data integrity issue with resource.");
    }
}