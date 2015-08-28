/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.activiti.engine.impl.persistence.entity;

import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.activiti.bpmn.model.Event;
import org.activiti.bpmn.model.EventDefinition;
import org.activiti.bpmn.model.FlowElement;
import org.activiti.bpmn.model.TimerEventDefinition;
import org.activiti.engine.ActivitiException;
import org.activiti.engine.ActivitiIllegalArgumentException;
import org.activiti.engine.ProcessEngineConfiguration;
import org.activiti.engine.delegate.Expression;
import org.activiti.engine.delegate.VariableScope;
import org.activiti.engine.delegate.event.ActivitiEventType;
import org.activiti.engine.delegate.event.impl.ActivitiEventBuilder;
import org.activiti.engine.impl.JobQueryImpl;
import org.activiti.engine.impl.Page;
import org.activiti.engine.impl.asyncexecutor.AsyncExecutor;
import org.activiti.engine.impl.calendar.BusinessCalendar;
import org.activiti.engine.impl.calendar.CycleBusinessCalendar;
import org.activiti.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.activiti.engine.impl.cfg.TransactionListener;
import org.activiti.engine.impl.cfg.TransactionState;
import org.activiti.engine.impl.context.Context;
import org.activiti.engine.impl.el.NoExecutionVariableScope;
import org.activiti.engine.impl.interceptor.CommandContext;
import org.activiti.engine.impl.jobexecutor.AsyncJobAddedNotification;
import org.activiti.engine.impl.jobexecutor.JobAddedNotification;
import org.activiti.engine.impl.jobexecutor.JobExecutor;
import org.activiti.engine.impl.jobexecutor.JobHandler;
import org.activiti.engine.impl.jobexecutor.TimerEventHandler;
import org.activiti.engine.impl.jobexecutor.TimerStartEventJobHandler;
import org.activiti.engine.impl.persistence.CachedPersistentObjectMatcher;
import org.activiti.engine.impl.util.ProcessDefinitionUtil;
import org.activiti.engine.runtime.Job;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Tom Baeyens
 * @author Daniel Meyer
 * @author Joram Barrez
 */
public class JobEntityManagerImpl extends AbstractEntityManager<JobEntity> implements JobEntityManager {
  
  private static final Logger logger = LoggerFactory.getLogger(JobEntityManagerImpl.class);
  
  protected static final List<Class<? extends JobEntity>> ENTITY_SUBCLASSES = Arrays.asList(TimerEntity.class, MessageEntity.class);
  
  @Override
  public Class<JobEntity> getManagedPersistentObject() {
    return JobEntity.class;
  }
  
  @Override
  public List<Class<? extends JobEntity>> getManagedPersistentObjectSubClasses() {
    return ENTITY_SUBCLASSES;
  }
  
  @Override
  public void insert(JobEntity jobEntity, boolean fireCreateEvent) {

    // add link to execution
    if (jobEntity.getExecutionId() != null) {
      ExecutionEntity execution = Context.getCommandContext().getExecutionEntityManager().findExecutionById(jobEntity.getExecutionId());
      execution.getJobs().add(jobEntity);

      // Inherit tenant if (if applicable)
      if (execution.getTenantId() != null) {
        jobEntity.setTenantId(execution.getTenantId());
      }
    }

    super.insert(jobEntity, fireCreateEvent);
  }

  @Override
  public void send(MessageEntity message) {

    ProcessEngineConfigurationImpl processEngineConfiguration = Context.getProcessEngineConfiguration();

    if (processEngineConfiguration.isAsyncExecutorEnabled()) {

      // If the async executor is enabled, we need to set the duedate of
      // the job to the current date + the default lock time.
      // This is cope with the case where the async job executor or the
      // process engine goes down
      // before executing the job. This way, other async job executors can
      // pick the job up after the max lock time.
      Date dueDate = new Date(processEngineConfiguration.getClock().getCurrentTime().getTime() + processEngineConfiguration.getAsyncExecutor().getAsyncJobLockTimeInMillis());
      message.setDuedate(dueDate);
      message.setLockExpirationTime(null); // was set before, but to be quickly picked up needs to be set to null

    } else if (!processEngineConfiguration.isJobExecutorActivate()) {

      // If the async executor is disabled AND there is no old school job
      // executor, The job needs to be picked up as soon as possible. So the due date is now set to the current time
      message.setDuedate(processEngineConfiguration.getClock().getCurrentTime());
      message.setLockExpirationTime(null); // was set before, but to be quickly picked up needs to be set to null
    }

    insert(message);
    if (processEngineConfiguration.isAsyncExecutorEnabled()) {
      hintAsyncExecutor(message);
    } else {
      hintJobExecutor(message);
    }
  }

  @Override
  public void schedule(TimerEntity timer) {
    Date duedate = timer.getDuedate();
    if (duedate == null) {
      throw new ActivitiIllegalArgumentException("duedate is null");
    }

    insert(timer);

    ProcessEngineConfiguration engineConfiguration = Context.getProcessEngineConfiguration();
    if (engineConfiguration.isAsyncExecutorEnabled() == false && timer.getDuedate().getTime() <= (engineConfiguration.getClock().getCurrentTime().getTime())) {

      hintJobExecutor(timer);
    }
  }

  @Override
  public void retryAsyncJob(JobEntity job) {
    AsyncExecutor asyncExecutor = Context.getProcessEngineConfiguration().getAsyncExecutor();
    try {
    	
    	// If a job has to be retried, we wait for a certain amount of time,
    	// otherwise the job will be continuously be retried without delay (and thus seriously stressing the database).
	    Thread.sleep(asyncExecutor.getRetryWaitTimeInMillis());
	    
    } catch (InterruptedException e) {
    }
    asyncExecutor.executeAsyncJob(job);
  }

  protected void hintAsyncExecutor(JobEntity job) {
    AsyncExecutor asyncExecutor = Context.getProcessEngineConfiguration().getAsyncExecutor();

    // notify job executor:
    TransactionListener transactionListener = new AsyncJobAddedNotification(job, asyncExecutor);
    Context.getCommandContext().getTransactionContext().addTransactionListener(TransactionState.COMMITTED, transactionListener);
  }

  protected void hintJobExecutor(JobEntity job) {
    JobExecutor jobExecutor = Context.getProcessEngineConfiguration().getJobExecutor();

    // notify job executor:
    TransactionListener transactionListener = new JobAddedNotification(jobExecutor);
    Context.getCommandContext().getTransactionContext().addTransactionListener(TransactionState.COMMITTED, transactionListener);
  }

  @Override
  public JobEntity findJobById(String jobId) {
    return (JobEntity) getDbSqlSession().selectOne("selectJob", jobId);
  }

  @Override
  @SuppressWarnings("unchecked")
  public List<JobEntity> findNextJobsToExecute(Page page) {
    ProcessEngineConfiguration processEngineConfig = Context.getProcessEngineConfiguration();
    Date now = processEngineConfig.getClock().getCurrentTime();
    return getDbSqlSession().selectList("selectNextJobsToExecute", now, page);
  }

  @Override
  @SuppressWarnings("unchecked")
  public List<JobEntity> findNextTimerJobsToExecute(Page page) {
    ProcessEngineConfiguration processEngineConfig = Context.getProcessEngineConfiguration();
    Date now = processEngineConfig.getClock().getCurrentTime();
    return getDbSqlSession().selectList("selectNextTimerJobsToExecute", now, page);
  }

  @Override
  @SuppressWarnings("unchecked")
  public List<JobEntity> findAsyncJobsDueToExecute(Page page) {
    ProcessEngineConfiguration processEngineConfig = Context.getProcessEngineConfiguration();
    Date now = processEngineConfig.getClock().getCurrentTime();
    return getDbSqlSession().selectList("selectAsyncJobsDueToExecute", now, page);
  }

  @Override
  @SuppressWarnings("unchecked")
  public List<JobEntity> findJobsByLockOwner(String lockOwner, int start, int maxNrOfJobs) {
    return getDbSqlSession().selectList("selectJobsByLockOwner", lockOwner, start, maxNrOfJobs);
  }

  @Override
  @SuppressWarnings("unchecked")
  public List<JobEntity> findJobsByExecutionId(final String executionId) {
    return getList("selectJobsByExecutionId", executionId, new CachedPersistentObjectMatcher<JobEntity>() {
      @Override
      public boolean isRetained(JobEntity jobEntity) {
        return jobEntity.getExecutionId() != null && jobEntity.getExecutionId().equals(executionId);
      }
    }, true);
  }

  @Override
  @SuppressWarnings("unchecked")
  public List<JobEntity> findExclusiveJobsToExecute(String processInstanceId) {
    Map<String, Object> params = new HashMap<String, Object>();
    params.put("pid", processInstanceId);
    params.put("now", Context.getProcessEngineConfiguration().getClock().getCurrentTime());
    return getDbSqlSession().selectList("selectExclusiveJobsToExecute", params);
  }

  @Override
  @SuppressWarnings("unchecked")
  public List<TimerEntity> findUnlockedTimersByDuedate(Date duedate, Page page) {
    final String query = "selectUnlockedTimersByDuedate";
    return getDbSqlSession().selectList(query, duedate, page);
  }

  @Override
  @SuppressWarnings("unchecked")
  public List<TimerEntity> findTimersByExecutionId(String executionId) {
    return getDbSqlSession().selectList("selectTimersByExecutionId", executionId);
  }

  @Override
  @SuppressWarnings("unchecked")
  public List<Job> findJobsByQueryCriteria(JobQueryImpl jobQuery, Page page) {
    final String query = "selectJobByQueryCriteria";
    return getDbSqlSession().selectList(query, jobQuery, page);
  }
  
  @Override
  @SuppressWarnings("unchecked")
  public List<Job> findJobsByTypeAndProcessDefinitionIds(String jobHandlerType, List<String> processDefinitionIds) {
    Map<String, Object> params = new HashMap<String, Object>(2);
    params.put("handlerType", jobHandlerType);
    
    if (processDefinitionIds != null && processDefinitionIds.size() > 0) {
      params.put("processDefinitionIds", processDefinitionIds);
    }
    return getDbSqlSession().selectList("selectJobsByTypeAndProcessDefinitionIds", params);
  }
  
  @Override
  @SuppressWarnings("unchecked")
  public List<Job> findJobsByTypeAndProcessDefinitionKeyNoTenantId(String jobHandlerType, String processDefinitionKey) {
     Map<String, String> params = new HashMap<String, String>(2);
     params.put("handlerType", jobHandlerType);
     params.put("processDefinitionKey", processDefinitionKey);
     return getDbSqlSession().selectList("selectJobByTypeAndProcessDefinitionKeyNoTenantId", params);
  }
  
  @Override
  @SuppressWarnings("unchecked")
  public List<Job> findJobsByTypeAndProcessDefinitionKeyAndTenantId(String jobHandlerType, String processDefinitionKey, String tenantId) {
     Map<String, String> params = new HashMap<String, String>(3);
     params.put("handlerType", jobHandlerType);
     params.put("processDefinitionKey", processDefinitionKey);
     params.put("tenantId", tenantId);
     return getDbSqlSession().selectList("selectJobByTypeAndProcessDefinitionKeyAndTenantId", params);
  }
  
  @Override
  @SuppressWarnings("unchecked")
  public List<Job> findJobsByTypeAndProcessDefinitionId(String jobHandlerType, String processDefinitionId) {
     Map<String, String> params = new HashMap<String, String>(2);
     params.put("handlerType", jobHandlerType);
     params.put("processDefinitionId", processDefinitionId);
     return getDbSqlSession().selectList("selectJobByTypeAndProcessDefinitionId", params);
  }
  
  @Override
  public long findJobCountByQueryCriteria(JobQueryImpl jobQuery) {
    return (Long) getDbSqlSession().selectOne("selectJobCountByQueryCriteria", jobQuery);
  }

  @Override
  public void updateJobTenantIdForDeployment(String deploymentId, String newTenantId) {
    HashMap<String, Object> params = new HashMap<String, Object>();
    params.put("deploymentId", deploymentId);
    params.put("tenantId", newTenantId);
    getDbSqlSession().update("updateJobTenantIdForDeployment", params);
  }

  @Override
  public void delete(JobEntity jobEntity) {
    super.delete(jobEntity);

    ByteArrayRef exceptionByteArrayRef = jobEntity.getExceptionByteArrayRef();

    // Also delete the job's exception byte array
    exceptionByteArrayRef.delete();

    // remove link to execution
    if (jobEntity.getExecutionId() != null) {
      ExecutionEntity execution = Context.getCommandContext().getExecutionEntityManager().findExecutionById(jobEntity.getExecutionId());
      execution.getJobs().remove(this);
    }
    
    // Send event
    if (Context.getProcessEngineConfiguration().getEventDispatcher().isEnabled()) {
      Context.getProcessEngineConfiguration().getEventDispatcher().dispatchEvent(ActivitiEventBuilder.createEntityEvent(ActivitiEventType.ENTITY_DELETED, this));
    }
  }
  
  // Job Execution logic ////////////////////////////////////////////////////////////////////
  
  @Override
  public void execute(JobEntity jobEntity) {
    if (jobEntity instanceof MessageEntity) {
      executeMessageJob(jobEntity);
    } else if (jobEntity instanceof TimerEntity) {
      executeTimerJob((TimerEntity) jobEntity);
    } 
  }

  protected void executeJobHandler(JobEntity jobEntity) {
    CommandContext commandContext = Context.getCommandContext();
    
    ExecutionEntity execution = null;
    if (jobEntity.getExecutionId() != null) {
      execution = commandContext.getExecutionEntityManager().findExecutionById(jobEntity.getExecutionId());
    }

    Map<String, JobHandler> jobHandlers = Context.getProcessEngineConfiguration().getJobHandlers();
    JobHandler jobHandler = jobHandlers.get(jobEntity.getJobHandlerType());
    jobHandler.execute(jobEntity, jobEntity.getJobHandlerConfiguration(), execution, commandContext);
  }
  
  protected void executeMessageJob(JobEntity jobEntity) {
    executeJobHandler(jobEntity);
    delete(jobEntity);
  }
  
  protected void executeTimerJob(TimerEntity timerEntity) {

    CommandContext commandContext = Context.getCommandContext();
    
    // set endDate if it was set to the definition
    restoreExtraData(commandContext, timerEntity);

    if (timerEntity.getDuedate() != null && !isValidTime(timerEntity, timerEntity.getDuedate())) {
      if (logger.isDebugEnabled()) {
        logger.debug("Timer {} fired. but the dueDate is after the endDate.  Deleting timer.", timerEntity.getId());
      }
      delete(timerEntity);
      return;
    }

    executeJobHandler(timerEntity);

    if (logger.isDebugEnabled()) {
      logger.debug("Timer {} fired. Deleting timer.", timerEntity.getId());
    }
    delete(timerEntity);

    if (timerEntity.getRepeat() != null) {
      int repeatValue = calculateRepeatValue(timerEntity);
      if (repeatValue != 0) {
        if (repeatValue > 0) {
          setNewRepeat(timerEntity, repeatValue);
        }
        Date newTimer = calculateNextTimer(timerEntity);
        if (newTimer != null && isValidTime(timerEntity, newTimer)) {
          TimerEntity te = new TimerEntity(timerEntity);
          te.setDuedate(newTimer);
          Context.getCommandContext().getJobEntityManager().schedule(te);
        }
      }
    }
  }
  
  protected void restoreExtraData(CommandContext commandContext, TimerEntity timerEntity) {
    String activityId = timerEntity.getJobHandlerConfiguration();

    if (timerEntity.getJobHandlerType().equalsIgnoreCase(TimerStartEventJobHandler.TYPE)) {

      activityId = TimerEventHandler.getActivityIdFromConfiguration(timerEntity.getJobHandlerConfiguration());
      String endDateExpressionString = TimerEventHandler.getEndDateFromConfiguration(timerEntity.getJobHandlerConfiguration());

      if (endDateExpressionString != null) {
        Expression endDateExpression = Context.getProcessEngineConfiguration().getExpressionManager().createExpression(endDateExpressionString);

        String endDateString = null;

        BusinessCalendar businessCalendar = Context.getProcessEngineConfiguration().getBusinessCalendarManager().getBusinessCalendar(CycleBusinessCalendar.NAME);

        VariableScope executionEntity = null;
        if (timerEntity.getExecutionId() != null) {
          executionEntity = commandContext.getExecutionEntityManager().findExecutionById(timerEntity.getExecutionId());
        }
        
        if (executionEntity == null) {
          executionEntity = NoExecutionVariableScope.getSharedInstance();
        }

        if (endDateExpression != null) {
          Object endDateValue = endDateExpression.getValue(executionEntity);
          if (endDateValue instanceof String) {
            endDateString = (String) endDateValue;
          } else if (endDateValue instanceof Date) {
            timerEntity.setEndDate((Date) endDateValue);
          } else {
            throw new ActivitiException("Timer '" + ((ExecutionEntity) executionEntity).getActivityId()
                + "' was not configured with a valid duration/time, either hand in a java.util.Date or a String in format 'yyyy-MM-dd'T'hh:mm:ss'");
          }

          if (timerEntity.getEndDate() == null) {
            timerEntity.setEndDate(businessCalendar.resolveEndDate(endDateString));
          }
        }
      }
    }

    int maxIterations = 1;
    if (timerEntity.getProcessDefinitionId() != null) {
      org.activiti.bpmn.model.Process process = ProcessDefinitionUtil.getProcess(timerEntity.getProcessDefinitionId());
      maxIterations = getMaxIterations(process, activityId);
      if (maxIterations <= 1) {
        maxIterations = getMaxIterations(process, activityId);
      }
    }
    timerEntity.setMaxIterations(maxIterations);
  }
  
  protected int getMaxIterations(org.activiti.bpmn.model.Process process, String activityId) {
    FlowElement flowElement = process.getFlowElement(activityId, true);
    if (flowElement != null) {
      if (flowElement instanceof Event) {
        
        Event event = (Event) flowElement;
        List<EventDefinition> eventDefinitions = event.getEventDefinitions();
        
        if (eventDefinitions != null) {
          
          for (EventDefinition eventDefinition : eventDefinitions) {
            if (eventDefinition instanceof TimerEventDefinition) {
              TimerEventDefinition timerEventDefinition = (TimerEventDefinition) eventDefinition;
              if (timerEventDefinition.getTimeCycle() != null) {
                return calculateMaxIterationsValue(timerEventDefinition.getTimeCycle());
              }
            }
          }
          
        }
        
      }
    }
    return -1;
  }
  
  protected int calculateMaxIterationsValue(String originalExpression) {
    int times = Integer.MAX_VALUE;
    List<String> expression = Arrays.asList(originalExpression.split("/"));
    if (expression.size() > 1 && expression.get(0).startsWith("R")) {
      times = Integer.MAX_VALUE;
      if (expression.get(0).length() > 1) {
        times = Integer.parseInt(expression.get(0).substring(1));
      }
    }
    return times;
  }
  
  protected int calculateRepeatValue(TimerEntity timerEntity) {
    int times = -1;
    List<String> expression = Arrays.asList(timerEntity.getRepeat().split("/"));
    if (expression.size() > 1 && expression.get(0).startsWith("R") && expression.get(0).length() > 1) {
      times = Integer.parseInt(expression.get(0).substring(1));
      if (times > 0) {
        times--;
      }
    }
    return times;
  }

  protected void setNewRepeat(TimerEntity timerEntity, int newRepeatValue) {
    List<String> expression = Arrays.asList(timerEntity.getRepeat().split("/"));
    expression = expression.subList(1, expression.size());
    StringBuilder repeatBuilder = new StringBuilder("R");
    repeatBuilder.append(newRepeatValue);
    for (String value : expression) {
      repeatBuilder.append("/");
      repeatBuilder.append(value);
    }
    timerEntity.setRepeat(repeatBuilder.toString());
  }
  
  protected boolean isValidTime(TimerEntity timerEntity, Date newTimerDate) {
    BusinessCalendar businessCalendar = Context.getProcessEngineConfiguration().getBusinessCalendarManager().getBusinessCalendar(CycleBusinessCalendar.NAME);
    return businessCalendar.validateDuedate(timerEntity.getRepeat(), timerEntity.getMaxIterations(), timerEntity.getEndDate(), newTimerDate);
  }
  
  protected Date calculateNextTimer(TimerEntity timerEntity) {
    BusinessCalendar businessCalendar = Context.getProcessEngineConfiguration().getBusinessCalendarManager().getBusinessCalendar(CycleBusinessCalendar.NAME);
    return businessCalendar.resolveDuedate(timerEntity.getRepeat(), timerEntity.getMaxIterations());
  }

}
