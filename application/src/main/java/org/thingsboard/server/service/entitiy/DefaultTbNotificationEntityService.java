/**
 * Copyright © 2016-2022 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.service.entitiy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.thingsboard.rule.engine.api.msg.DeviceCredentialsUpdateNotificationMsg;
import org.thingsboard.server.cluster.TbClusterService;
import org.thingsboard.server.common.data.DataConstants;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.HasName;
import org.thingsboard.server.common.data.Tenant;
import org.thingsboard.server.common.data.alarm.Alarm;
import org.thingsboard.server.common.data.audit.ActionType;
import org.thingsboard.server.common.data.edge.Edge;
import org.thingsboard.server.common.data.edge.EdgeEventActionType;
import org.thingsboard.server.common.data.edge.EdgeEventType;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.EdgeId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.RuleChainId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.plugin.ComponentLifecycleEvent;
import org.thingsboard.server.common.data.relation.EntityRelation;
import org.thingsboard.server.common.data.rule.RuleChain;
import org.thingsboard.server.common.data.rule.RuleChainType;
import org.thingsboard.server.common.data.security.DeviceCredentials;
import org.thingsboard.server.common.msg.TbMsg;
import org.thingsboard.server.common.msg.TbMsgDataType;
import org.thingsboard.server.common.msg.TbMsgMetaData;
import org.thingsboard.server.queue.util.TbCoreComponent;
import org.thingsboard.server.service.action.EntityActionService;
import org.thingsboard.server.service.gateway_device.GatewayNotificationsService;
import org.thingsboard.server.service.security.model.SecurityUser;

import java.util.List;

@Slf4j
@Service
@TbCoreComponent
@RequiredArgsConstructor
public class DefaultTbNotificationEntityService implements TbNotificationEntityService {
    private static final ObjectMapper json = new ObjectMapper();

    private final EntityActionService entityActionService;
    private final TbClusterService tbClusterService;
    private final GatewayNotificationsService gatewayNotificationsService;

    @Override
    public <E extends HasName, I extends EntityId> void notifyEntity(TenantId tenantId, I entityId, E entity, CustomerId customerId,
                                                                     ActionType actionType, SecurityUser user, Exception e,
                                                                     Object... additionalInfo) {
        logEntityAction(tenantId, entityId, entity, customerId, actionType, user, e, additionalInfo);
    }

    @Override
    public <E extends HasName, I extends EntityId> void notifyDeleteEntity(TenantId tenantId, I entityId, E entity,
                                                                           CustomerId customerId, ActionType actionType,
                                                                           List<EdgeId> relatedEdgeIds,
                                                                           SecurityUser user, Object... additionalInfo) {
        logEntityAction(tenantId, entityId, entity, customerId, actionType, user, additionalInfo);
        sendDeleteNotificationMsg(tenantId, entityId, entity, relatedEdgeIds);
    }

    public void notifyDeleteAlarm(TenantId tenantId, Alarm alarm, EntityId originatorId,
                                  CustomerId customerId,
                                  List<EdgeId> relatedEdgeIds,
                                  SecurityUser user,
                                  String body, Object... additionalInfo) {
        logEntityAction(tenantId, originatorId, alarm, customerId, ActionType.DELETED, user, additionalInfo);
        sendAlarmDeleteNotificationMsg(tenantId, alarm, relatedEdgeIds, body);
    }

    @Override
    public void notifyDeleteRuleChain(TenantId tenantId, RuleChain ruleChain,
                                      List<EdgeId> relatedEdgeIds, SecurityUser user) {
        RuleChainId ruleChainId = ruleChain.getId();
        logEntityAction(tenantId, ruleChainId, ruleChain, null, ActionType.DELETED, user, null, ruleChainId.toString());
        if (RuleChainType.EDGE.equals(ruleChain.getType())) {
            sendDeleteNotificationMsg(tenantId, ruleChainId, relatedEdgeIds, null);
        }
    }

    @Override
    public <I extends EntityId> void notifySendMsgToEdgeService(TenantId tenantId, I entityId, EdgeEventActionType edgeEventActionType) {
        sendEntityNotificationMsg(tenantId, entityId, edgeEventActionType);
     }

    @Override
    public <E extends HasName, I extends EntityId> void notifyAssignOrUnassignEntityToCustomer(TenantId tenantId, I entityId,
                                                                                               CustomerId customerId, E entity,
                                                                                               ActionType actionType,
                                                                                               EdgeEventActionType edgeActionType,
                                                                                               SecurityUser user, boolean sendToEdge,
                                                                                               Object... additionalInfo) {
        logEntityAction(tenantId, entityId, entity, customerId, actionType, user, additionalInfo);

        if (sendToEdge) {
            sendEntityAssignToCustomerNotificationMsg(tenantId, entityId, customerId, edgeActionType);
        }
    }

    @Override
    public <E extends HasName, I extends EntityId> void notifyAssignOrUnassignEntityToEdge(TenantId tenantId, I entityId,
                                                                                           CustomerId customerId, EdgeId edgeId,
                                                                                           E entity, ActionType actionType,
                                                                                           SecurityUser user, Object... additionalInfo) {
        logEntityAction(tenantId, entityId, entity, customerId, actionType, user, additionalInfo);
        sendEntityAssignToEdgeNotificationMsg(tenantId, edgeId, entityId, edgeTypeByActionType(actionType));
    }

    @Override
    public void notifyCreateOruUpdateTenant(Tenant tenant, ComponentLifecycleEvent event) {
        tbClusterService.onTenantChange(tenant, null);
        tbClusterService.broadcastEntityStateChangeEvent(tenant.getId(), tenant.getId(), event);
    }

    @Override
    public void notifyDeleteTenant(Tenant tenant) {
        tbClusterService.onTenantDelete(tenant, null);
        tbClusterService.broadcastEntityStateChangeEvent(tenant.getId(), tenant.getId(), ComponentLifecycleEvent.DELETED);
    }

    @Override
    public void notifyCreateOrUpdateDevice(TenantId tenantId, DeviceId deviceId, CustomerId customerId,
                                           Device device, Device oldDevice, ActionType actionType,
                                           SecurityUser user, Object... additionalInfo) {
        tbClusterService.onDeviceUpdated(device, oldDevice);
        logEntityAction(tenantId, deviceId, device, customerId, actionType, user, additionalInfo);
    }

    @Override
    public void notifyDeleteDevice(TenantId tenantId, DeviceId deviceId, CustomerId customerId, Device device,
                                   List<EdgeId> relatedEdgeIds, SecurityUser user, Object... additionalInfo) {
        gatewayNotificationsService.onDeviceDeleted(device);
        tbClusterService.onDeviceDeleted(device, null);

        notifyDeleteEntity(tenantId, deviceId, device, customerId, ActionType.DELETED, relatedEdgeIds, user, additionalInfo);
    }

    @Override
    public void notifyUpdateDeviceCredentials(TenantId tenantId, DeviceId deviceId, CustomerId customerId, Device device,
                                              DeviceCredentials deviceCredentials, SecurityUser user) {
        tbClusterService.pushMsgToCore(new DeviceCredentialsUpdateNotificationMsg(tenantId, deviceCredentials.getDeviceId(), deviceCredentials), null);
        sendEntityNotificationMsg(tenantId, deviceId, EdgeEventActionType.CREDENTIALS_UPDATED);
        logEntityAction(tenantId, deviceId, device, customerId, ActionType.CREDENTIALS_UPDATED, user, deviceCredentials);
    }

    @Override
    public void notifyAssignDeviceToTenant(TenantId tenantId, TenantId newTenantId, DeviceId deviceId, CustomerId customerId,
                                           Device device, Tenant tenant, SecurityUser user, Object... additionalInfo) {
        logEntityAction(tenantId, deviceId, device, customerId, ActionType.ASSIGNED_TO_TENANT, user, additionalInfo);
        pushAssignedFromNotification(tenant, newTenantId, device);
    }

    @Override
    public <E extends HasName, I extends EntityId> void notifyCreateOrUpdateEntity(TenantId tenantId, I entityId, E entity, CustomerId customerId, ActionType actionType, SecurityUser user, Object... additionalInfo) {
        logEntityAction(tenantId, entityId, entity, customerId, actionType, user, additionalInfo);
        if (actionType == ActionType.UPDATED) {
            sendEntityNotificationMsg(tenantId, entityId, EdgeEventActionType.UPDATED);
        }
    }

    @Override
    public void notifyEdge(TenantId tenantId, EdgeId edgeId, CustomerId customerId, Edge edge, ActionType actionType,
                           SecurityUser user, Object... additionalInfo) {
        ComponentLifecycleEvent lifecycleEvent;
        EdgeEventActionType edgeEventActionType = null;
        switch (actionType) {
            case ADDED:
                lifecycleEvent = ComponentLifecycleEvent.CREATED;
                break;
            case UPDATED:
                lifecycleEvent = ComponentLifecycleEvent.UPDATED;
                break;
            case ASSIGNED_TO_CUSTOMER:
                lifecycleEvent = ComponentLifecycleEvent.UPDATED;
                edgeEventActionType = EdgeEventActionType.ASSIGNED_TO_CUSTOMER;
                break;
            case UNASSIGNED_FROM_CUSTOMER:
                lifecycleEvent = ComponentLifecycleEvent.UPDATED;
                edgeEventActionType = EdgeEventActionType.UNASSIGNED_FROM_CUSTOMER;
                break;
            case DELETED:
                lifecycleEvent = ComponentLifecycleEvent.DELETED;
                break;
            default:
                throw new IllegalArgumentException("Unknown actionType: " + actionType);
        }

        tbClusterService.broadcastEntityStateChangeEvent(tenantId, edgeId, lifecycleEvent);
        logEntityAction(tenantId, edgeId, edge, customerId, actionType, user, additionalInfo);

        //Send notification to edge
        if (edgeEventActionType != null) {
            sendEntityAssignToCustomerNotificationMsg(tenantId, edgeId, customerId, edgeEventActionType);
        }
    }

    @Override
    public void notifyCreateOrUpdateAlarm(Alarm alarm, ActionType actionType, SecurityUser user, Object... additionalInfo) {
        logEntityAction(alarm.getTenantId(), alarm.getOriginator(), alarm, alarm.getCustomerId(), actionType, user, additionalInfo);
        sendEntityNotificationMsg(alarm.getTenantId(), alarm.getId(), edgeTypeByActionType(actionType));
    }

    @Override
    public <E extends HasName, I extends EntityId> void notifyCreateOrUpdateOrDelete(TenantId tenantId, CustomerId customerId,
                                                                                     I entityId, E entity, SecurityUser user,
                                                                                     ActionType actionType, boolean sendNotifyMsgToEdge, Exception e,
                                                                                     Object... additionalInfo) {
        notifyEntity(tenantId, entityId, entity, customerId, actionType, user, e, additionalInfo);
        if (sendNotifyMsgToEdge) {
            sendEntityNotificationMsg(tenantId, entityId, edgeTypeByActionType(actionType));
        }
    }

    @Override
    public void notifyCreateOrUpdateOrDeleteRelation(TenantId tenantId, CustomerId customerId,
                                                     EntityRelation relation, SecurityUser user,
                                                     ActionType actionType, Exception e,
                                                     Object... additionalInfo) {
        notifyEntity(tenantId, relation.getFrom(), null, customerId, actionType, user, e, additionalInfo);
        notifyEntity(tenantId, relation.getTo(), null, customerId, actionType, user, e, additionalInfo);
        if (e == null) {
            try {
                if (!relation.getFrom().getEntityType().equals(EntityType.EDGE) &&
                        !relation.getTo().getEntityType().equals(EntityType.EDGE)) {
                    sendNotificationMsgToEdgeService(tenantId, null, null, json.writeValueAsString(relation),
                            EdgeEventType.RELATION, edgeTypeByActionType(actionType));
                }
            } catch (Exception e1) {
                log.warn("Failed to push relation to core: {}", relation, e1);
            }
        }
    }

    private <E extends HasName, I extends EntityId> void logEntityAction(TenantId tenantId, I entityId, E entity, CustomerId customerId,
                                                                         ActionType actionType, SecurityUser user, Object... additionalInfo) {
        logEntityAction(tenantId, entityId, entity, customerId, actionType, user, null, additionalInfo);
    }

    private <E extends HasName, I extends EntityId> void logEntityAction(TenantId tenantId, I entityId, E entity, CustomerId customerId,
                                                                         ActionType actionType, SecurityUser user, Exception e, Object... additionalInfo) {
        if (user != null) {
            entityActionService.logEntityAction(user, entityId, entity, customerId, actionType, e, additionalInfo);
        } else if (e == null) {
            entityActionService.pushEntityActionToRuleEngine(entityId, entity, tenantId, customerId, actionType, null, additionalInfo);
        }
    }

    private void sendEntityNotificationMsg(TenantId tenantId, EntityId entityId, EdgeEventActionType action) {
        sendNotificationMsgToEdgeService(tenantId, null, entityId, null, null, action);
    }

    private void sendEntityAssignToCustomerNotificationMsg(TenantId tenantId, EntityId entityId, CustomerId customerId, EdgeEventActionType action) {
        try {
            sendNotificationMsgToEdgeService(tenantId, null, entityId, json.writeValueAsString(customerId), null, action);
        } catch (Exception e) {
            log.warn("Failed to push assign/unassign to/from customer to core: {}", customerId, e);
        }
    }

    protected void sendAlarmDeleteNotificationMsg(TenantId tenantId, Alarm alarm, List<EdgeId> edgeIds, String body) {
        try {
            sendDeleteNotificationMsg(tenantId, alarm.getId(), edgeIds, body);
        } catch (Exception e) {
            log.warn("Failed to push delete msg to core: {}", alarm, e);
        }
    }

    protected <E extends HasName, I extends EntityId> void sendDeleteNotificationMsg(TenantId tenantId, I entityId, E entity,
                                                                                     List<EdgeId> edgeIds) {
        try {
            sendDeleteNotificationMsg(tenantId, entityId, edgeIds, null);
        } catch (Exception e) {
            log.warn("Failed to push delete msg to core: {}", entity, e);
        }
    }

    private void sendDeleteNotificationMsg(TenantId tenantId, EntityId entityId, List<EdgeId> edgeIds, String body) {
        if (edgeIds != null && !edgeIds.isEmpty()) {
            for (EdgeId edgeId : edgeIds) {
                sendNotificationMsgToEdgeService(tenantId, edgeId, entityId, body, null, EdgeEventActionType.DELETED);
            }
        }
    }

    private void sendEntityAssignToEdgeNotificationMsg(TenantId tenantId, EdgeId edgeId, EntityId entityId, EdgeEventActionType action) {
        sendNotificationMsgToEdgeService(tenantId, edgeId, entityId, null, null, action);
    }

    private void sendNotificationMsgToEdgeService(TenantId tenantId, EdgeId edgeId, EntityId entityId, String body, EdgeEventType type, EdgeEventActionType action) {
        tbClusterService.sendNotificationMsgToEdgeService(tenantId, edgeId, entityId, body, type, action);
    }

    private void pushAssignedFromNotification(Tenant currentTenant, TenantId newTenantId, Device assignedDevice) {
        String data = entityToStr(assignedDevice);
        if (data != null) {
            TbMsg tbMsg = TbMsg.newMsg(DataConstants.ENTITY_ASSIGNED_FROM_TENANT, assignedDevice.getId(), assignedDevice.getCustomerId(), getMetaDataForAssignedFrom(currentTenant), TbMsgDataType.JSON, data);
            tbClusterService.pushMsgToRuleEngine(newTenantId, assignedDevice.getId(), tbMsg, null);
        }
    }

    private TbMsgMetaData getMetaDataForAssignedFrom(Tenant tenant) {
        TbMsgMetaData metaData = new TbMsgMetaData();
        metaData.putValue("assignedFromTenantId", tenant.getId().getId().toString());
        metaData.putValue("assignedFromTenantName", tenant.getName());
        return metaData;
    }

    private <E extends HasName> String entityToStr(E entity) {
        try {
            return json.writeValueAsString(json.valueToTree(entity));
        } catch (JsonProcessingException e) {
            log.warn("[{}] Failed to convert entity to string!", entity, e);
        }
        return null;
    }

    private EdgeEventActionType edgeTypeByActionType(ActionType actionType) {
        switch (actionType) {
            case ADDED:
                return EdgeEventActionType.ADDED;
            case UPDATED:
                return EdgeEventActionType.UPDATED;
            case ALARM_ACK:
                return EdgeEventActionType.ALARM_ACK;
            case ALARM_CLEAR:
                return EdgeEventActionType.ALARM_CLEAR;
            case DELETED:
                return EdgeEventActionType.DELETED;
            case RELATION_ADD_OR_UPDATE:
                return EdgeEventActionType.RELATION_ADD_OR_UPDATE;
            case RELATION_DELETED:
                return EdgeEventActionType.RELATION_DELETED;
            case ASSIGNED_TO_EDGE:
                return EdgeEventActionType.ASSIGNED_TO_EDGE;
            case UNASSIGNED_FROM_EDGE:
                return EdgeEventActionType.UNASSIGNED_FROM_EDGE;
            default:
                return null;
        }
    }
}