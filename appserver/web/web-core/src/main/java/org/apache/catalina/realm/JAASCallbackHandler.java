/*
 * Copyright (c) 1997-2018 Oracle and/or its affiliates. All rights reserved.
 * Copyright 2004 The Apache Software Foundation
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

package org.apache.catalina.realm;

import java.io.IOException;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.NameCallback;
import javax.security.auth.callback.PasswordCallback;
import javax.security.auth.callback.UnsupportedCallbackException;

/**
 * <p>
 * Implementation of the JAAS <strong>CallbackHandler</code> interface, used to negotiate delivery of the username and
 * credentials that were specified to our constructor. No interaction with the user is required (or possible).
 * </p>
 *
 * @author Craig R. McClanahan
 * @version $Revision: 1.2 $ $Date: 2005/12/08 01:27:52 $
 */
public class JAASCallbackHandler implements CallbackHandler {

    // ----------------------------------------------------- Instance Variables

    /**
     * The password to be authenticated with.
     */
    protected char[] password;

    /**
     * The associated <code>JAASRealm</code> instance.
     */
    protected JAASRealm realm;

    /**
     * The username to be authenticated with.
     */
    protected String username;



    // ------------------------------------------------------------ Constructor

    /**
     * Construct a callback handler configured with the specified values.
     *
     * @param realm Our associated JAASRealm instance
     * @param username Username to be authenticated with
     * @param password Password to be authenticated with
     */
    public JAASCallbackHandler(JAASRealm realm, String username, char[] password) {
        super();
        this.realm = realm;
        this.username = username;
        this.password = ((password != null) ? ((char[]) password.clone()) : null);
    }



    // --------------------------------------------------------- Public Methods

    /**
     * Retrieve the information requested in the provided Callbacks. This implementation only recognizes
     * <code>NameCallback</code> and <code>PasswordCallback</code> instances.
     *
     * @param callbacks The set of callbacks to be processed
     *
     * @exception IOException if an input/output error occurs
     * @exception UnsupportedCallbackException if the login method requests an unsupported callback type
     */
    @Override
    public void handle(Callback callbacks[]) throws IOException, UnsupportedCallbackException {
        for (Callback callback : callbacks) {

            if (callback instanceof NameCallback) {
                if (realm.getDebug() >= 3) {
                    realm.log("Returning username " + username);
                }
                ((NameCallback) callback).setName(username);
            } else if (callback instanceof PasswordCallback) {
                final char[] passwordcontents;
                if (password != null) {
                    passwordcontents = password.clone();
                } else {
                    passwordcontents = new char[0];
                }
                ((PasswordCallback) callback).setPassword(passwordcontents);
            } else {
                throw new UnsupportedCallbackException(callback);
            }

        }

    }

}
