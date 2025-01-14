/**
 * Copyright © 2016-2023 The Thingsboard Authors
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
package org.thingsboard.server.service.edge.rpc.processor.entityview;

import com.datastax.oss.driver.api.core.uuid.Uuids;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.util.Pair;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.EntityView;
import org.thingsboard.server.common.data.StringUtils;
import org.thingsboard.server.common.data.id.AssetId;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.EntityViewId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.gen.edge.v1.EdgeEntityType;
import org.thingsboard.server.gen.edge.v1.EdgeVersion;
import org.thingsboard.server.gen.edge.v1.EntityViewUpdateMsg;
import org.thingsboard.server.service.edge.rpc.processor.BaseEdgeProcessor;
import org.thingsboard.server.service.edge.rpc.utils.EdgeVersionUtils;

import java.util.UUID;

@Slf4j
public abstract class BaseEntityViewProcessor extends BaseEdgeProcessor {

    protected Pair<Boolean, Boolean> saveOrUpdateEntityView(TenantId tenantId, EntityViewId entityViewId, EntityViewUpdateMsg entityViewUpdateMsg, EdgeVersion edgeVersion) {
        boolean created = false;
        boolean entityViewNameUpdated = false;
        EntityView entityView = EdgeVersionUtils.isEdgeVersionOlderThan_3_6_2(edgeVersion)
                ? createEntityView(tenantId, entityViewId, entityViewUpdateMsg)
                : JacksonUtil.fromStringIgnoreUnknownProperties(entityViewUpdateMsg.getEntity(), EntityView.class);
        if (entityView == null) {
            throw new RuntimeException("[{" + tenantId + "}] entityViewUpdateMsg {" + entityViewUpdateMsg + "} cannot be converted to entity view");
        }
        EntityView entityViewById = entityViewService.findEntityViewById(tenantId, entityViewId);
        if (entityViewById == null) {
            created = true;
            entityView.setId(null);
        } else {
            entityView.setId(entityViewId);
        }
        String entityViewName = entityView.getName();
        EntityView entityViewByName = entityViewService.findEntityViewByTenantIdAndName(tenantId, entityViewName);
        if (entityViewByName != null && !entityViewByName.getId().equals(entityViewId)) {
            entityViewName = entityViewName + "_" + StringUtils.randomAlphanumeric(15);
            log.warn("[{}] Entity view with name {} already exists. Renaming entity view name to {}",
                    tenantId, entityView.getName(), entityViewName);
            entityViewNameUpdated = true;
        }
        entityView.setName(entityViewName);
        setCustomerId(tenantId, created ? null : entityViewById.getCustomerId(), entityView, entityViewUpdateMsg, edgeVersion);

        entityViewValidator.validate(entityView, EntityView::getTenantId);
        if (created) {
            entityView.setId(entityViewId);
        }
        entityViewService.saveEntityView(entityView, false);
        return Pair.of(created, entityViewNameUpdated);
    }

    private EntityView createEntityView(TenantId tenantId, EntityViewId entityViewId, EntityViewUpdateMsg entityViewUpdateMsg) {
        EntityView entityView = new EntityView();
        entityView.setTenantId(tenantId);
        entityView.setCreatedTime(Uuids.unixTimestamp(entityViewId.getId()));
        entityView.setName(entityViewUpdateMsg.getName());
        entityView.setType(entityViewUpdateMsg.getType());

        entityView.setAdditionalInfo(entityViewUpdateMsg.hasAdditionalInfo() ?
                JacksonUtil.toJsonNode(entityViewUpdateMsg.getAdditionalInfo()) : null);

        CustomerId customerId = safeGetCustomerId(entityViewUpdateMsg.getCustomerIdMSB(), entityViewUpdateMsg.getCustomerIdLSB());
        entityView.setCustomerId(customerId);

        UUID entityIdUUID = safeGetUUID(entityViewUpdateMsg.getEntityIdMSB(), entityViewUpdateMsg.getEntityIdLSB());
        if (EdgeEntityType.DEVICE.equals(entityViewUpdateMsg.getEntityType())) {
            entityView.setEntityId(entityIdUUID != null ? new DeviceId(entityIdUUID) : null);
        } else if (EdgeEntityType.ASSET.equals(entityViewUpdateMsg.getEntityType())) {
            entityView.setEntityId(entityIdUUID != null ? new AssetId(entityIdUUID) : null);
        }
        return entityView;
    }

    protected abstract void setCustomerId(TenantId tenantId, CustomerId customerId, EntityView entityView, EntityViewUpdateMsg entityViewUpdateMsg, EdgeVersion edgeVersion);
}
