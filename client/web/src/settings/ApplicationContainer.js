/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import React, { useEffect, useState } from 'react'
import { Switch, Route } from 'react-router-dom'
import { useDispatch, useSelector } from 'react-redux'
import {
  saveToPresignedBucket,
  selectServiceToS3BucketMap,
} from '../settings/ducks'
import {
  fetchSettings,
  fetchConfig,
  updateConfig,
  selectAllSettings,
  dismissError,
  selectLoading,
  selectConfig,
  selectConfigLoading,
  selectConfigError,
  selectConfigMessage,
} from './ducks'

import { ApplicationComponent } from './ApplicationComponent'
import { ConfirmModal } from './ConfirmModal'
import { selectDbOptions, selectOsOptions } from '../options/ducks'
import { fetchTenantsThunk, selectAllTenants } from '../tenant/ducks'
import { fetchTiersThunk, selectAllTiers } from '../tier/ducks'

export function ApplicationContainer(props) {
  const EFS = 'EFS'
  const FSX = 'FSX'
  const LINUX = 'LINUX'

  const dispatch = useDispatch()
  const appConfig = useSelector(selectConfig)
  const configError = useSelector(selectConfigError)
  const configLoading = useSelector(selectConfigLoading)
  const configMessage = useSelector(selectConfigMessage)
  const dbOptions = useSelector(selectDbOptions)
  const loading = useSelector(selectLoading)
  const osOptions = useSelector(selectOsOptions)
  const serviceToS3BucketMap = useSelector(selectServiceToS3BucketMap)
  const settings = useSelector(selectAllSettings)

  const hasTenants = useSelector((state) => {
    return selectAllTenants(state)?.length > 0
  })
  const tiers = useSelector(selectAllTiers)

  const [modal, setModal] = useState(false)
  const [file, setFile] = useState({})
  const [formValues, setFormValues] = useState(null)

  const toggleModal = () => setModal(!modal)

  let settingsObj = {}

  if (!!settings) {
    // covert array to object to reference values easier
    settings.forEach((setting) => {
      settingsObj[setting.name] = setting
    })
  }

  useEffect(() => {
    const settingsResponse = dispatch(fetchSettings())
    return () => {
      if (settingsResponse.PromiseStatus === 'pending') {
        settingsResponse.abort()
      }
      dispatch(dismissError())
    }
  }, [dispatch])

  useEffect(() => {
    const fetchConfigResponse = dispatch(fetchConfig())
    return () => {
      if (fetchConfigResponse.PromiseStatus === 'pending') {
        fetchConfigResponse.abort()
      }
      dispatch(dismissError())
    }
  }, [dispatch])

  useEffect(() => {
    const fetchTenantsResponse = dispatch(fetchTenantsThunk())
    return () => {
      if (fetchTenantsResponse.PromiseStatus === 'pending') {
        fetchTenantsResponse.abort()
      }
      dismissError(dismissError())
    }
  }, [dispatch])

  useEffect(() => {
    const fetchTiersResponse = dispatch(fetchTiersThunk())
    return () => {
      if (fetchTiersResponse.PromiseStatus === 'pending') {
        fetchTiersResponse.abort()
      }
      dismissError(dismissError())
    }
  }, [dispatch])

  useEffect(() => {
    Object.keys(file).forEach((fn) => {
      const dbFile = file[fn]
      const url = serviceToS3BucketMap[fn]
      if (dbFile && url) {
        dispatch(
          saveToPresignedBucket({
            dbFile,
            url,
          })
        )
      }
    })
  }, [serviceToS3BucketMap, appConfig, dispatch, file])

  const presubmitCheck = (values) => {
    setFormValues(values)
    if (hasTenants) {
      setModal(true)
    } else {
      setModal(false)
      updateConfiguration(values)
    }
  }

  const confirmSubmit = () => {
    setModal(false)
    updateConfiguration(formValues)
  }

  const getFormattedTime = (timeString) => {
    // Expecting timeString to be HH:MM:SS
    let parts = timeString.split(':')
    if (parts.length === 3) {
      parts = parts.splice(0, 2) //Trim the trailing :00. Server doesn't need seconds
    }
    return parts.join(':')
  }

  const updateConfiguration = async (values) => {
    const isMatch = (pw, encryptedPw) => {
      return encryptedPw.substring(0, 8) === pw
    }

    try {
      const { services, billing, provisionBilling, ...rest } = values
      let cleanedServicesMap = {}
      for (var serviceIndex in services) {
        let thisService = services[serviceIndex]
        if (thisService.tombstone) continue
        // update the tier config
        // TODO: validate tiers against Tier Service
        let cleanedTiersMap = {}
        for (var tierName in thisService.tiers) {
          const {
            filesystem,
            database,
            provisionDb,
            provisionFS,
            ...rest
          } = thisService.tiers[tierName]
          let { filesystemLifecycle, ...cleanedFs } = filesystem
          let {
            weeklyMaintenanceDay,
            weeklyMaintenanceTime: time,
            ...cleanedFsx
          } = cleanedFs.fsx
          const weeklyTime = getFormattedTime(time)
          const fsx = {
            ...cleanedFsx,
            weeklyMaintenanceTime: `${weeklyMaintenanceDay}:${weeklyTime}`,
          }
          cleanedFs = {
            ...cleanedFs,
            efs: thisService.filesystem?.fileSystemType === EFS ? cleanedFs.efs : null,
            fsx: thisService.filesystem?.fileSystemType === FSX ? fsx : null,
            fileSystemType: thisService.filesystem?.fileSystemType,
          }
          const {
            port,
            hasEncryptedPassword,
            encryptedPassword,
            bootstrapFilename,
            ...restDb
          } = database
          // If we detected an encrypted password coming in, and it looks like they haven't changed it
          // then send the encrypted password back to the server. Otherwise send what they changed.
          const cleanedDb = {
            ...restDb,
            password:
              hasEncryptedPassword &&
              isMatch(restDb.password, encryptedPassword)
                ? encryptedPassword
                : restDb.password,
          }
          cleanedTiersMap[tierName] = {
            ...rest,
            filesystem: provisionFS ? cleanedFs : null,
            database: provisionDb ? cleanedDb : null,
          }
        }
        // update the service config
        const {
          name,
          windowsVersion,
          operatingSystem,
          filesystem,
          tombstone,
          ...rest
        } = thisService
        cleanedServicesMap[name] = {
          ...rest,
          name,
          operatingSystem: operatingSystem === LINUX ? LINUX : windowsVersion,
          tiers: cleanedTiersMap,
        }
      }

      // compile the complete appConfig
      const configToSend = {
        ...rest,
        billing: provisionBilling ? billing : null,
        services: cleanedServicesMap,
      }
      dispatch(updateConfig(configToSend))
    } catch (e) {
      console.error(e)
    }
  }

  const handleFileSelected = (newFile) => {
    file[newFile.serviceName] = newFile.file
    setFile(file)
  }

  return (
    <Switch>
      <Route
        path="/application"
        exact={true}
        name="Application"
        render={(props) => (
          <>
            <ConfirmModal
              headerText="WARNING"
              confirmationText="Warning: The changes you've made will be applied to all existing tenants that are configured with the default settings (as well as future tenants)."
              toggle={toggleModal}
              modal={modal}
              confirmSubmit={confirmSubmit}
            ></ConfirmModal>
            <ApplicationComponent
              appConfig={appConfig}
              dbOptions={dbOptions}
              hasTenants={hasTenants}
              onFileSelected={handleFileSelected}
              osOptions={osOptions}
              settings={settings}
              settingsObj={settingsObj}
              error={configError}
              message={configMessage}
              loading={
                loading === 'idle' && configLoading === 'idle'
                  ? 'idle'
                  : 'pending'
              }
              updateConfiguration={presubmitCheck}
              tiers={tiers}
              {...props}
            />
          </>
        )}
      ></Route>
    </Switch>
  )
}

export default ApplicationContainer
