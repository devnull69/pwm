/*
 * Password Management Servlets (PWM)
 * http://www.pwm-project.org
 *
 * Copyright (c) 2006-2009 Novell, Inc.
 * Copyright (c) 2009-2016 The PWM Project
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package password.pwm.http;

import com.novell.ldapchai.ChaiFactory;
import com.novell.ldapchai.ChaiUser;
import com.novell.ldapchai.exception.ChaiUnavailableException;
import com.novell.ldapchai.provider.ChaiProvider;
import password.pwm.Permission;
import password.pwm.PwmApplication;
import password.pwm.bean.UserIdentity;
import password.pwm.bean.UserInfoBean;
import password.pwm.config.PwmSetting;
import password.pwm.config.UserPermission;
import password.pwm.config.profile.HelpdeskProfile;
import password.pwm.config.profile.Profile;
import password.pwm.config.profile.ProfileType;
import password.pwm.config.profile.UpdateAttributesProfile;
import password.pwm.error.ErrorInformation;
import password.pwm.error.PwmError;
import password.pwm.error.PwmUnrecoverableException;
import password.pwm.ldap.LdapOperationsHelper;
import password.pwm.ldap.LdapPermissionTester;
import password.pwm.ldap.LdapUserDataReader;
import password.pwm.ldap.UserDataReader;
import password.pwm.util.PasswordData;
import password.pwm.util.logging.PwmLogger;
import password.pwm.util.macro.MacroMachine;

import java.io.Serializable;
import java.util.List;

/**
 * Wraps an <i>HttpSession</i> to provide additional PWM-related session
 * management activities.
 * <p/>
 *
 * @author Jason D. Rivard
 */
public class SessionManager implements Serializable {
// ------------------------------ FIELDS ------------------------------

    private static final PwmLogger LOGGER = PwmLogger.forClass(SessionManager.class);

    private ChaiProvider chaiProvider;

    final private PwmSession pwmSession;

    private transient UserDataReader userDataReader;



// --------------------------- CONSTRUCTORS ---------------------------

    public SessionManager(final PwmSession pwmSession) {
        this.pwmSession = pwmSession;
    }

// --------------------- GETTER / SETTER METHODS ---------------------

    public ChaiProvider getChaiProvider()
            throws ChaiUnavailableException, PwmUnrecoverableException
    {
        if (chaiProvider == null) {
            throw new PwmUnrecoverableException(new ErrorInformation(PwmError.ERROR_AUTHENTICATION_REQUIRED,"ldap connection is not available for session"));
        }
        return chaiProvider;
    }

    public void setChaiProvider(final ChaiProvider chaiProvider) {
        this.chaiProvider = chaiProvider;
    }

    public void updateUserPassword(final PwmApplication pwmApplication, final UserIdentity userIdentity, final PasswordData userPassword)
            throws PwmUnrecoverableException
    {
        this.closeConnections();

        try {
            this.chaiProvider = LdapOperationsHelper.createChaiProvider(
                    pwmSession.getLabel(),
                    userIdentity.getLdapProfile(pwmApplication.getConfig()),
                    pwmApplication.getConfig(),
                    userIdentity.getUserDN(),
                    userPassword
            );
            final String userDN = userIdentity.getUserDN();
            ChaiFactory.createChaiEntry(userDN,chaiProvider).isValid();
        } catch (ChaiUnavailableException e) {
            final ErrorInformation errorInformation = new ErrorInformation(
                    PwmError.ERROR_DIRECTORY_UNAVAILABLE,
                    "error updating cached chaiProvider connection/password: " + e.getMessage());
            throw new PwmUnrecoverableException(errorInformation);
        }
    }


// ------------------------ CANONICAL METHODS ------------------------

    protected void finalize()
            throws Throwable {
        super.finalize();
        this.closeConnections();
    }

    public void closeConnections() {
        if (chaiProvider != null) {
            try {
                LOGGER.debug(pwmSession.getLabel(), "closing user ldap connection");
                chaiProvider.close();
                chaiProvider = null;
            } catch (Exception e) {
                LOGGER.error(pwmSession.getLabel(), "error while closing user connection: " + e.getMessage());
            }
        }

        if (userDataReader != null) {
            userDataReader = null;
        }
    }

// -------------------------- OTHER METHODS --------------------------

    public ChaiUser getActor(final PwmApplication pwmApplication)
            throws ChaiUnavailableException, PwmUnrecoverableException {

        if (!pwmSession.isAuthenticated()) {
            throw new IllegalStateException("user not logged in");
        }

        final UserIdentity userDN = pwmSession.getUserInfoBean().getUserIdentity();

        if (userDN == null || userDN.getUserDN() == null || userDN.getUserDN().length() < 1) {
            throw new IllegalStateException("user not logged in");
        }

        return ChaiFactory.createChaiUser(userDN.getUserDN(), this.getChaiProvider());
    }

    public boolean hasActiveLdapConnection() {
        return this.chaiProvider != null && this.chaiProvider.isConnected();
    }

    public ChaiUser getActor(final PwmApplication pwmApplication, final UserIdentity userIdentity)
            throws PwmUnrecoverableException
    {
        try {
            if (!pwmSession.isAuthenticated()) {
                throw new PwmUnrecoverableException(PwmError.ERROR_AUTHENTICATION_REQUIRED);
            }
            final UserIdentity thisIdentity = pwmSession.getUserInfoBean().getUserIdentity();
            if (thisIdentity.getLdapProfileID() == null || userIdentity.getLdapProfileID() == null) {
                throw new PwmUnrecoverableException(PwmError.ERROR_NO_LDAP_CONNECTION);
            }
            final ChaiProvider provider = this.getChaiProvider();
            return ChaiFactory.createChaiUser(userIdentity.getUserDN(), provider);
        } catch (ChaiUnavailableException e) {
            throw PwmUnrecoverableException.fromChaiException(e);
        }
    }

    public UserDataReader getUserDataReader(final PwmApplication pwmApplication)
            throws PwmUnrecoverableException
    {
        if (pwmSession == null || !pwmSession.isAuthenticated()) {
            return null;
        }

        if (userDataReader == null) {
            /*
            userDataReader = LdapUserDataReader.appProxiedReader(pwmApplication,
                    pwmSession.getUserInfoBean().getUserIdentity());
                    */
            final UserIdentity userIdentity = pwmSession.getUserInfoBean().getUserIdentity();
            try {
                userDataReader = LdapUserDataReader.selfProxiedReader(pwmApplication, pwmSession, userIdentity);
            } catch (ChaiUnavailableException e) {
                throw PwmUnrecoverableException.fromChaiException(e);
            }
        }
        return userDataReader;
    }

    public void clearUserDataReader() {
        userDataReader = null;
    }

    public void incrementRequestCounterKey() {
        if (this.pwmSession != null) {
            this.pwmSession.getLoginInfoBean().setReqCounter(
                    this.pwmSession.getLoginInfoBean().getReqCounter() + 1)
            ;

            LOGGER.trace(pwmSession.getLabel(), "incremented request counter to " + this.pwmSession.getLoginInfoBean().getReqCounter());
        }
    }

    public boolean checkPermission(final PwmApplication pwmApplication, final Permission permission)
            throws PwmUnrecoverableException
    {
        final boolean devDebugMode = pwmApplication.getConfig().isDevDebugMode();
        if (devDebugMode) {
            LOGGER.trace(pwmSession.getLabel(), String.format("entering checkPermission(%s, %s, %s)", permission, pwmSession, pwmApplication));
        }

        if (!pwmSession.isAuthenticated()) {
            if (devDebugMode) {
                LOGGER.trace(pwmSession.getLabel(), "user is not authenticated, returning false for permission check");
            }
            return false;
        }

        Permission.PERMISSION_STATUS status = pwmSession.getUserSessionDataCacheBean().getPermission(permission);
        if (status == Permission.PERMISSION_STATUS.UNCHECKED) {
            if (devDebugMode) {
                LOGGER.debug(pwmSession.getLabel(), String.format("checking permission %s for user %s", permission.toString(), pwmSession.getUserInfoBean().getUserIdentity().toDelimitedKey()));
            }

            final PwmSetting setting = permission.getPwmSetting();
            final List<UserPermission> userPermission = pwmApplication.getConfig().readSettingAsUserPermission(setting);
            final boolean result = LdapPermissionTester.testUserPermissions(pwmApplication, pwmSession.getLabel(), pwmSession.getUserInfoBean().getUserIdentity(), userPermission);
            status = result ? Permission.PERMISSION_STATUS.GRANTED : Permission.PERMISSION_STATUS.DENIED;
            pwmSession.getUserSessionDataCacheBean().setPermission(permission, status);
            LOGGER.debug(pwmSession.getLabel(), String.format("permission %s for user %s is %s",
                    permission.toString(), pwmSession.getUserInfoBean().getUserIdentity().toDelimitedKey(),
                    status.toString()));
        }
        return status == Permission.PERMISSION_STATUS.GRANTED;
    }

    public MacroMachine getMacroMachine(final PwmApplication pwmApplication)
            throws PwmUnrecoverableException
    {
        final UserDataReader userDataReader = this.getUserDataReader(pwmApplication);
        final UserInfoBean userInfoBean = pwmSession.isAuthenticated()
                ? pwmSession.getUserInfoBean()
                : null;
        return new MacroMachine(pwmApplication, pwmSession.getLabel(), userInfoBean, pwmSession.getLoginInfoBean(), userDataReader);
    }

    public Profile getProfile(final PwmApplication pwmApplication, final ProfileType profileType) {
        if (profileType.isAuthenticated() && !pwmSession.isAuthenticated()) {
            return null;
        }
        final String profileID = pwmSession.getUserInfoBean().getProfileIDs().get(profileType);
        if (profileID != null) {
            return pwmApplication.getConfig().profileMap(profileType).get(profileID);
        }
        return null;
    }

    public HelpdeskProfile getHelpdeskProfile(final PwmApplication pwmApplication) {
        return (HelpdeskProfile)getProfile(pwmApplication, ProfileType.Helpdesk);
    }

    public UpdateAttributesProfile getUpdateAttributeProfile(final PwmApplication pwmApplication) {
        return (UpdateAttributesProfile)getProfile(pwmApplication, ProfileType.UpdateAttributes);
    }
}
