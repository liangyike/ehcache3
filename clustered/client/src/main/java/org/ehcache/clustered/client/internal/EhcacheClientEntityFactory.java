/*
 * Copyright Terracotta, Inc.
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

package org.ehcache.clustered.client.internal;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.ehcache.clustered.lock.client.VoltronReadWriteLock;
import org.ehcache.clustered.common.ServerSideConfiguration;
import org.ehcache.clustered.lock.client.VoltronReadWriteLock.Hold;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terracotta.connection.Connection;
import org.terracotta.connection.entity.EntityRef;
import org.terracotta.exception.EntityAlreadyExistsException;
import org.terracotta.exception.EntityNotFoundException;
import org.terracotta.exception.EntityNotProvidedException;
import org.terracotta.exception.EntityVersionMismatchException;

public class EhcacheClientEntityFactory {

  private static final Logger LOGGER = LoggerFactory.getLogger(EhcacheClientEntityFactory.class);

  private static final long ENTITY_VERSION = 1L;

  private final Connection connection;
  private final Map<String, Hold> maintenanceHolds = new ConcurrentHashMap<String, Hold>();

  public EhcacheClientEntityFactory(Connection connection) {
    this.connection = connection;
  }

  public boolean acquireLeadership(String entityIdentifier) {
    VoltronReadWriteLock lock = createAccessLockFor(entityIdentifier);

    Hold hold = lock.tryWriteLock();
    if (hold == null) {
      return false;
    } else {
      maintenanceHolds.put(entityIdentifier, hold);
      return true;
    }
  }

  public void abandonLeadership(String entityIdentifier) {
    Hold hold = maintenanceHolds.remove(entityIdentifier);
    if (hold == null) {
      throw new IllegalMonitorStateException("Leadership was never held");
    } else {
      hold.unlock();
    }
  }

  /**
   * Attempts to create and configure the {@code EhcacheActiveEntity} in the Ehcache clustered server.
   *
   * @param identifier the instance identifier for the {@code EhcacheActiveEntity}
   * @param config the {@code EhcacheActiveEntity} configuration
   *
   * @throws EntityAlreadyExistsException if the {@code EhcacheActiveEntity} for {@code identifier} already exists
   * @throws EhcacheEntityCreationException if an error preventing {@code EhcacheActiveEntity} creation was raised;
   *        this is generally resulting from another client holding operational leadership preventing this client
   *        from becoming leader and creating the {@code EhcacheActiveEntity} instance
   */
  public void create(final String identifier, final ServerSideConfiguration config)
      throws EntityAlreadyExistsException, EhcacheEntityCreationException {
    Hold existingMaintenance = maintenanceHolds.get(identifier);
    Hold localMaintenance = null;
    if (existingMaintenance == null) {
      localMaintenance = createAccessLockFor(identifier).tryWriteLock();
    }
    if (existingMaintenance == null && localMaintenance == null) {
      throw new EhcacheEntityCreationException("Unable to create entity for cluster id "
              + identifier + ": another client owns the maintenance lease");
    } else {
      try {
        EntityRef<EhcacheClientEntity, UUID> ref = getEntityRef(identifier);
        try {
          while (true) {
            ref.create(UUID.randomUUID());
            try {
              EhcacheClientEntity entity = ref.fetchEntity();
              try {
                configure(entity, config);
                return;
              } finally {
                entity.close();
              }
            } catch (EntityNotFoundException e) {
                //continue;
            }
          }
        } catch (EntityNotProvidedException e) {
          LOGGER.error("Unable to create entity for cluster id {}", identifier, e);
          throw new AssertionError(e);
        } catch (EntityVersionMismatchException e) {
          LOGGER.error("Unable to create entity for cluster id {}", identifier, e);
          throw new AssertionError(e);
        }
      } finally {
        if (localMaintenance != null) {
          localMaintenance.unlock();
        }
      }
    }
  }

  public EhcacheClientEntity retrieve(String identifier, ServerSideConfiguration config) throws EntityNotFoundException, IllegalArgumentException, EhcacheEntityBusyException {
    try {
      Hold fetchHold = createAccessLockFor(identifier).tryReadLock();
      if (fetchHold == null) {
        throw new EhcacheEntityBusyException("Unable to retrieve entity for cluster id "
                + identifier + ": another client owns the maintenance lease");
      } else {
        EhcacheClientEntity entity = getEntityRef(identifier).fetchEntity();
        /*
         * Currently entities are never closed as doing so can stall the client
         * when the server is dead.  Instead the connection is forcibly closed,
         * which suits our purposes since that will unlock the fetchHold too.
         */
        boolean validated = false;
        try {
          validate(entity, config);
          validated = true;
          return entity;
        } finally {
          if (!validated) {
            entity.close();
            fetchHold.unlock();
          }
        }
      }
    } catch (EntityVersionMismatchException e) {
      LOGGER.error("Unable to retrieve entity for cluster id {}", identifier, e);
      throw new AssertionError(e);
    }
  }

  public void destroy(final String identifier) throws EhcacheEntityNotFoundException, EhcacheEntityBusyException {
    Hold existingMaintenance = maintenanceHolds.get(identifier);
    Hold localMaintenance = null;
    if (existingMaintenance == null) {
      localMaintenance = createAccessLockFor(identifier).tryWriteLock();
    }
    if (existingMaintenance == null && localMaintenance == null) {
      throw new EhcacheEntityBusyException("Destroy operation failed; " + identifier + " maintenance lease held");
    } else {
      try {
        EntityRef<EhcacheClientEntity, UUID> ref = getEntityRef(identifier);
        try {
          if (!ref.tryDestroy()) {
            throw new EhcacheEntityBusyException("Destroy operation failed; " + identifier + " caches in use by other clients");
          }
        } catch (EntityNotProvidedException e) {
          LOGGER.error("Unable to delete entity for cluster id {}", identifier, e);
          throw new AssertionError(e);
        } catch (EntityNotFoundException e) {
          throw new EhcacheEntityNotFoundException(e);
        } catch (EhcacheEntityBusyException e) {
          throw new EhcacheEntityBusyException(e);
        }
      } finally {
        if (localMaintenance != null) {
          localMaintenance.unlock();
        }
      }
    }
  }

  private VoltronReadWriteLock createAccessLockFor(String entityIdentifier) {
    return new VoltronReadWriteLock(connection, "EhcacheClientEntityFactory-AccessLock-" + entityIdentifier);
  }

  private EntityRef<EhcacheClientEntity, UUID> getEntityRef(String identifier) {
    try {
      return connection.getEntityRef(EhcacheClientEntity.class, ENTITY_VERSION, identifier);
    } catch (EntityNotProvidedException e) {
      LOGGER.error("Unable to get entity for cluster id {}", identifier, e);
      throw new AssertionError(e);
    }
  }

  private void validate(EhcacheClientEntity entity, ServerSideConfiguration config) {
    entity.validate(config);
  }

  private void configure(EhcacheClientEntity entity, ServerSideConfiguration config) {
    entity.configure(config);
  }
}