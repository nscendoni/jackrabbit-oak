/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.jackrabbit.oak.security.user;

import org.apache.jackrabbit.guava.common.base.Joiner;
import org.apache.jackrabbit.guava.common.collect.Iterables;
import org.apache.jackrabbit.oak.api.CommitFailedException;
import org.apache.jackrabbit.oak.api.Root;
import org.apache.jackrabbit.oak.api.Tree;
import org.apache.jackrabbit.oak.commons.LongUtils;
import org.apache.jackrabbit.oak.plugins.tree.TreeUtil;
import org.apache.jackrabbit.util.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.AccessDeniedException;
import java.security.Principal;
import java.util.HashMap;
import java.util.Set;

import static org.apache.jackrabbit.oak.security.user.UserPrincipalProvider.MEMBERSHIP_THRESHOLD;

public class PrincipalCommitterThread extends Thread {
    Tree authorizableNode;
    Set<Principal> groupPrincipals;
    HashMap committerThreadMap;
    long expiration;
    Root root;

    private static final Logger log = LoggerFactory.getLogger(PrincipalCommitterThread.class);

    public PrincipalCommitterThread(Tree authorizableNode, Set<Principal> groupPrincipals, long expiration, Root root, HashMap committerThreadMap) {
        this.authorizableNode = authorizableNode;
        this.groupPrincipals = groupPrincipals;
        this.committerThreadMap = committerThreadMap;
        this.expiration = expiration;
        this.root = root;
    }

    @Override
    public void run() {
        super.run();
        // Do the commit
        try {
            root.refresh();

            Tree cache = authorizableNode.getChild(CacheConstants.REP_CACHE);
            if (!cache.exists()) {
                if (groupPrincipals.size() <= MEMBERSHIP_THRESHOLD) {
                    log.debug("Omit cache creation for user without group membership at {}", authorizableNode.getPath());
                    return;
                } else {
                    log.debug("Create new group membership cache at {}", authorizableNode.getPath());
                    cache = TreeUtil.addChild(authorizableNode, CacheConstants.REP_CACHE, CacheConstants.NT_REP_CACHE);
                }
            }

            cache.setProperty(CacheConstants.REP_EXPIRATION, LongUtils.calculateExpirationTime(expiration));
            String value = (groupPrincipals.isEmpty()) ? "" : Joiner.on(",").join(Iterables.transform(groupPrincipals, input -> Text.escape(input.getName())));
            cache.setProperty(CacheConstants.REP_GROUP_PRINCIPAL_NAMES, value);

            root.commit(CacheValidatorProvider.asCommitAttributes());
            log.debug("Cached group membership at {}", authorizableNode.getPath());

        } catch (AccessDeniedException | CommitFailedException e) {
            log.debug("Failed to cache group membership: {}", e.getMessage());
        } finally {
            log.debug("Removing thread from committerThreadMap for {}", authorizableNode.getPath());
            committerThreadMap.remove(authorizableNode.getPath());
            root.refresh();
        }

    }
}
