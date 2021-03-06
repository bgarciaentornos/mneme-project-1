/**********************************************************************************
 * $URL$
 * $Id$
 ***********************************************************************************
 *
 * Copyright (c) 2008, 2009, 2010, 2011, 2012, 2013, 2014, 2015 Etudes, Inc.
 * 
 * Portions completed before September 1, 2008
 * Copyright (c) 2007, 2008 The Regents of the University of Michigan & Foothill College, ETUDES Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
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
 *
 **********************************************************************************/

package org.etudes.mneme.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipOutputStream;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.etudes.mneme.api.Assessment;
import org.etudes.mneme.api.AssessmentPermissionException;
import org.etudes.mneme.api.AssessmentPolicyException;
import org.etudes.mneme.api.AssessmentService;
import org.etudes.mneme.api.AssessmentType;
import org.etudes.mneme.api.AttachmentService;
import org.etudes.mneme.api.GradesRejectsAssessmentException;
import org.etudes.mneme.api.GradesService;
import org.etudes.mneme.api.MnemeService;
import org.etudes.mneme.api.Part;
import org.etudes.mneme.api.PartDetail;
import org.etudes.mneme.api.Pool;
import org.etudes.mneme.api.PoolService;
import org.etudes.mneme.api.Question;
import org.etudes.mneme.api.QuestionService;
import org.etudes.mneme.api.ReviewTiming;
import org.etudes.mneme.api.SecurityService;
import org.etudes.mneme.api.Settings;
import org.etudes.mneme.api.SubmissionService;
import org.etudes.util.api.Translation;
import org.sakaiproject.db.api.SqlService;
import org.sakaiproject.entity.api.Reference;
import org.sakaiproject.event.api.EventTrackingService;
import org.sakaiproject.thread_local.api.ThreadLocalManager;
import org.sakaiproject.tool.api.SessionManager;
import org.sakaiproject.user.api.User;
import org.sakaiproject.user.api.UserDirectoryService;
import org.sakaiproject.util.StringUtil;

/**
 * AssessmentServiceImpl implements AssessmentService.
 */
public class AssessmentServiceImpl implements AssessmentService
{
	/** Our logger. */
	private static Log M_log = LogFactory.getLog(AssessmentServiceImpl.class);

	/** Dependency: AttachmentService */
	protected AttachmentService attachmentService = null;

	/** Dependency: EventTrackingService */
	protected EventTrackingService eventTrackingService = null;

	/** Dependency: ExportQtiService */
	protected ExportQtiServiceImpl exportService = null;

	/** Dependency: GradesService */
	protected GradesService gradesService = null;

	/** Dependency: PoolService */
	protected PoolService poolService = null;

	/** Dependency: QuestionService */
	protected QuestionService questionService = null;

	/** Dependency: SecurityService */
	protected SecurityService securityService = null;

	/** Dependency: SessionManager */
	protected SessionManager sessionManager = null;

	/** Dependency: SqlService */
	protected SqlService sqlService = null;

	/** Storage handler. */
	protected AssessmentStorage storage = null;

	/** Storage option map key for the option to use. */
	protected String storageKey = null;

	/** Map of registered PoolStorage options. */
	protected Map<String, AssessmentStorage> storgeOptions;

	/** Dependency: SubmissionService */
	protected SubmissionServiceImpl submissionService = null;

	/** Dependency: ThreadLocalManager. */
	protected ThreadLocalManager threadLocalManager = null;

	protected UserDirectoryService userDirectoryService = null;
	
	/** Configuration: if true, pre-load questions and pools, and cache assessments, in getContextAssessments (MN-1420 MN-1458), otherwise don't */
	boolean preLoadCache = true;

	/**
	 * {@inheritDoc}
	 */
	public Boolean allowEditAssessment(Assessment assessment)
	{
		if (assessment == null) throw new IllegalArgumentException();
		String userId = sessionManager.getCurrentSessionUserId();

		if (M_log.isDebugEnabled()) M_log.debug("allowEditAssessment: " + assessment.getId() + ": " + userId);

		// check permission - user must have MANAGE_PERMISSION in the context
		boolean ok = securityService.checkSecurity(userId, MnemeService.MANAGE_PERMISSION, assessment.getContext());

		// for FCE, user must have that permission, too
		if (ok && assessment.getFormalCourseEval())
		{
			ok = securityService.checkSecurity(userId, MnemeService.COURSE_EVAL_PERMISSION, assessment.getContext());
		}

		return ok;
	}

	/**
	 * {@inheritDoc}
	 */
	public Boolean allowGuest(String context)
	{
		if (context == null) throw new IllegalArgumentException();
		String userId = sessionManager.getCurrentSessionUserId();

		if (M_log.isDebugEnabled()) M_log.debug("allowGuest: " + context + ": " + userId);

		// check permission - user must have GUEST_PERMISSION in the context
		boolean ok = securityService.checkSecurity(userId, MnemeService.GUEST_PERMISSION, context);

		return ok;
	}

	/**
	 * {@inheritDoc}
	 */
	public Boolean allowListDeliveryAssessment(String context)
	{
		if (context == null) throw new IllegalArgumentException();
		String userId = sessionManager.getCurrentSessionUserId();

		if (M_log.isDebugEnabled()) M_log.debug("allowListDeliveryAssessment: " + context + ": " + userId);

		// check permission - user must have SUBMIT_PERMISSION or MANAGE in the context
		boolean ok = securityService.checkSecurity(userId, MnemeService.SUBMIT_PERMISSION, context)
				|| securityService.checkSecurity(userId, MnemeService.MANAGE_PERMISSION, context);

		return Boolean.valueOf(ok);
	}

	/**
	 * {@inheritDoc}
	 */
	public Boolean allowManageAssessments(String context)
	{
		if (context == null) throw new IllegalArgumentException();
		String userId = sessionManager.getCurrentSessionUserId();

		if (M_log.isDebugEnabled()) M_log.debug("allowManageAssessments: " + context + ": " + userId);

		// check permission - user must have MANAGE_PERMISSION in the context
		boolean ok = securityService.checkSecurity(userId, MnemeService.MANAGE_PERMISSION, context);

		return ok;
	}

	/**
	 * {@inheritDoc}
	 */
	public Boolean allowRemoveAssessment(Assessment assessment)
	{
		if (assessment == null) throw new IllegalArgumentException();

		if (M_log.isDebugEnabled()) M_log.debug("allowRemoveAssessment: " + assessment.getId());

		// user must have manage permission
		if (!this.allowManageAssessments(assessment.getContext())) return Boolean.FALSE;

		// check policy
		return satisfyAssessmentRemovalPolicy(assessment);
	}

	/**
	 * {@inheritDoc}
	 */

	public Boolean allowSetFormalCourseEvaluation(String context)
	{
		if (context == null) throw new IllegalArgumentException();
		String userId = sessionManager.getCurrentSessionUserId();

		if (M_log.isDebugEnabled()) M_log.debug("allowSetFormalCourseEvaluation: " + context + ": " + userId);

		// check permission - user must have COURSE_EVAL_PERMISSION in the context
		boolean ok = securityService.checkSecurity(userId, MnemeService.COURSE_EVAL_PERMISSION, context);

		return ok;
	}

	/**
	 * {@inheritDoc}
	 */
	public Boolean allowSubmit(String context)
	{
		if (context == null) throw new IllegalArgumentException();
		String userId = sessionManager.getCurrentSessionUserId();

		// check permission - user must have SUBMIT_PERMISSION in the context
		boolean ok = securityService.checkSecurity(userId, MnemeService.SUBMIT_PERMISSION, context);

		return ok;
	}

	/**
	 * {@inheritDoc}
	 */
	public void applyBaseDateTx(String context, int days)
	{
		if (context == null) throw new IllegalArgumentException("applyBaseDateTx: context is null");
		if (days == 0) return;

		try
		{
			// security check
			securityService.secure(sessionManager.getCurrentSessionUserId(), MnemeService.MANAGE_PERMISSION, context);

			// do this the slow way (i.e. not all in SQL) to avoid the y2038 bug and assure proper gradebook integration
			// see Etudes Jira MN-1125

			// get all assessments
			List<Assessment> assessments = getContextAssessments(context, AssessmentsSort.odate_a, Boolean.FALSE);

			GregorianCalendar gc = new GregorianCalendar();

			// for each one, apply the base date change
			for (Assessment assessment : assessments)
			{
				if (assessment.getDates().getAcceptUntilDate() != null)
				{
					gc.setTime(assessment.getDates().getAcceptUntilDate());
					gc.add(Calendar.DATE, days);
					assessment.getDates().setAcceptUntilDate(gc.getTime());
				}

				if (assessment.getDates().getDueDate() != null)
				{
					gc.setTime(assessment.getDates().getDueDate());
					gc.add(Calendar.DATE, days);
					assessment.getDates().setDueDate(gc.getTime());
				}

				if (assessment.getDates().getOpenDate() != null)
				{
					gc.setTime(assessment.getDates().getOpenDate());
					gc.add(Calendar.DATE, days);
					assessment.getDates().setOpenDate(gc.getTime());
				}

				if (assessment.getReview().getDate() != null)
				{
					gc.setTime(assessment.getReview().getDate());
					gc.add(Calendar.DATE, days);
					assessment.getReview().setDate(gc.getTime());
				}

				// save
				try
				{
					saveAssessment(assessment);
				}
				catch (AssessmentPermissionException e)
				{
					M_log.warn("applyBaseDateTx: " + assessment.getId() + " exception: " + e.toString());
				}
				catch (AssessmentPolicyException e)
				{
					M_log.warn("applyBaseDateTx: " + assessment.getId() + " exception: " + e.toString());
				}
			}
		}
		catch (AssessmentPermissionException e)
		{
			throw new RuntimeException("applyBaseDateTx: security check failed: " + e.toString());
		}
	}

	/**
	 * {@inheritDoc}
	 */
	public Assessment assessmentExists(String context, String title)
	{
		List<Assessment> assessments = getContextAssessments(context, AssessmentService.AssessmentsSort.cdate_a, Boolean.FALSE);
		for (Assessment candidate : assessments)
		{
			if (!StringUtil.different(candidate.getTitle().toLowerCase(), title.toLowerCase()))
			{
				return candidate;
			}
		}

		return null;
	}

	/**
	 * {@inheritDoc}
	 */
	public void clearStaleMintAssessments()
	{
		// give it a day
		Date stale = new Date();
		stale.setTime(stale.getTime() - (1000l * 60l * 60l * 24l));

		if (M_log.isDebugEnabled()) M_log.debug("clearStaleMintAssessments");

		List<String> ids = this.storage.clearStaleMintAssessments(stale);

		// events
		for (String id : ids)
		{
			eventTrackingService.post(eventTrackingService.newEvent(MnemeService.ASSESSMENT_DELETE, getAssessmentReference(id), true));
		}
	}

	/**
	 * {@inheritDoc}
	 */
	public Assessment copyAssessment(String context, Assessment assessment) throws AssessmentPermissionException
	{
		if (context == null) throw new IllegalArgumentException();
		if (assessment == null) throw new IllegalArgumentException();

		// security check
		securityService.secure(sessionManager.getCurrentSessionUserId(), MnemeService.MANAGE_PERMISSION, context);

		AssessmentImpl rv = doCopyAssessment(context, assessment, null, null, true, null);

		return rv;
	}

	/**
	 * {@inheritDoc}
	 */
	public Integer countAssessments(String context)
	{
		if (context == null) throw new IllegalArgumentException();

		if (M_log.isDebugEnabled()) M_log.debug("countAssessments: " + context);

		return this.storage.countAssessments(context);
	}

	/**
	 * Returns to uninitialized state.
	 */
	public void destroy()
	{
		M_log.info("destroy()");
	}

	/**
	 * {@inheritDoc}
	 */
	public Boolean existsAssessmentTitle(String title, String context)
	{
		if (M_log.isDebugEnabled()) M_log.debug("existsAssessment: title: " + title);

		return this.storage.existsAssessmentTitle(title, context);
	}		

	/**
	 * {@inheritDoc}
	 */
	public void exportAssessments(String context, String[] ids, ZipOutputStream zip) throws AssessmentPermissionException, IOException
	{
		this.exportService.exportAssessments(context, ids, zip);
	}

	/**
	 * {@inheritDoc}
	 */
	public List<Assessment> getArchivedAssessments(String context)
	{
		if (context == null) throw new IllegalArgumentException();

		if (M_log.isDebugEnabled()) M_log.debug("getArchivedAssessments: " + context);

		List<Assessment> rv = new ArrayList<Assessment>(this.storage.getArchivedAssessments(context, true));
		return rv;
	}

	/**
	 * {@inheritDoc}
	 */
	public List<Assessment> getArchivedAssessments(String context, boolean includeFce)
	{
		if (context == null) throw new IllegalArgumentException();

		if (M_log.isDebugEnabled()) M_log.debug("getArchivedAssessments: " + context);

		List<Assessment> rv = new ArrayList<Assessment>(this.storage.getArchivedAssessments(context, includeFce));
		return rv;
	}

	/**
	 * {@inheritDoc}
	 */
	public Assessment getAssessment(String id)
	{
		if (id == null) throw new IllegalArgumentException();

		// for thread-local caching
		String key = cacheKey(id);
		AssessmentImpl rv = (AssessmentImpl) this.threadLocalManager.get(key);
		if (rv != null)
		{
			// return a copy
			return this.storage.clone((AssessmentImpl) rv);
		}

		if (M_log.isDebugEnabled()) M_log.debug("getAssessment: " + id);

		rv = this.storage.getAssessment(id);

		// thread-local cache a copy
		if (rv != null) this.threadLocalManager.set(key, this.storage.clone((AssessmentImpl) rv));

		return rv;
	}

	/**
	 * {@inheritDoc}
	 */
	public List<Assessment> getAssessmentsNeedingResultsEmail()
	{
		List<Assessment> rv = new ArrayList<Assessment>();

		// this gets the candidates - but does not check the close dates
		List<AssessmentImpl> assessments = this.storage.getAssessmentsNeedingResultsEmail();

		// TODO: security?

		// filter in those that are closed now, and filter out any that are surveys but not formal course evaluations
		for (Assessment a : assessments)
		{
			// surveys that are not formal evals do not support this feature
			// if ((a.getType() == AssessmentType.survey) && (!a.getFormalCourseEval())) continue;
			// surveys that are not formal evals need to have view results clicked to support this feature
			if ((a.getType() == AssessmentType.survey) && (!a.getFormalCourseEval()) && !a.getFrozen()) continue;

			if (a.getDates().getIsClosed())
			{
				rv.add(a);
			}
		}

		return rv;
	}

	/**
	 * {@inheritDoc}
	 */
	public List<Assessment> getContextAssessments(String context, AssessmentsSort sort, Boolean publishedOnly)
	{
		if (context == null) throw new IllegalArgumentException();
		if (publishedOnly == null) throw new IllegalArgumentException();
		if (sort == null) sort = AssessmentsSort.cdate_a;

		if (M_log.isDebugEnabled()) M_log.debug("getContextAssessments: " + context + " sort: " + sort + " publishOnly: " + publishedOnly);

		if (this.preLoadCache)
		{
			// pre-load the questions & pools for all assessments in this context, needed for the validity checks on the assessments (parts).
			// publishedOnly == true is the case that needs this, but we do it always cause it's likely the caller will be checking validity.
			this.questionService.readAssessmentQuestions(context, publishedOnly);
			this.poolService.readAssessmentPools(context, publishedOnly);
		}

		List<Assessment> rv = new ArrayList<Assessment>(this.storage.getContextAssessments(context, sort, publishedOnly));

		if (this.preLoadCache)
		{
			// thread-local cache a copy of each found assessment
			for (Assessment assessment : rv)
			{
				String key = cacheKey(assessment.getId());
				this.threadLocalManager.set(key, this.storage.clone((AssessmentImpl) assessment));
			}
		}

		return rv;
	}

	/**
	 * {@inheritDoc}
	 */
	public List<Assessment> getFormalEvaluationsNeedingNotification()
	{
		// this gets the candidates - but does not check the close dates
		List<Assessment> assessments = new ArrayList<Assessment>(this.storage.getFormalEvaluationsNeedingNotification());

		// TODO: security?
		return assessments;
	}

	/**
	 * {@inheritDoc}
	 */
	public Date getMaxStartDate(String context)
	{
		Date maxDate = this.storage.getMaxStartDate(context);
		return maxDate;
	}

	/**
	 * {@inheritDoc}
	 */
	public Date getMinStartDate(String context)
	{
		Date minDate = this.storage.getMinStartDate(context);
		return minDate;
	}

	/**
	 * {@inheritDoc}
	 */
	@SuppressWarnings(
	{ "unchecked", "rawtypes" })
	public List<User> getSubmitUsers(String context)
	{
		if (M_log.isDebugEnabled()) M_log.debug("getSubmitUsers: " + context);

		// get the ids
		Set<String> ids = this.securityService.getUsersIsAllowed(MnemeService.SUBMIT_PERMISSION, context);

		// turn into users
		List<User> users = this.userDirectoryService.getUsers(ids);

		// sort - by user sort name
		Collections.sort(users, new Comparator()
		{
			public int compare(Object arg0, Object arg1)
			{
				int rv = ((User) arg0).getSortName().compareToIgnoreCase(((User) arg1).getSortName());

				return rv;
			}
		});

		return users;
	}

	/**
	 * Final initialization, once all dependencies are set.
	 */
	public void init()
	{
		try
		{
			// storage - as configured
			if (this.storageKey != null)
			{
				// if set to "SQL", replace with the current SQL vendor
				if ("SQL".equals(this.storageKey))
				{
					this.storageKey = sqlService.getVendor();
				}

				this.storage = this.storgeOptions.get(this.storageKey);
			}

			// use "default" if needed
			if (this.storage == null)
			{
				this.storage = this.storgeOptions.get("default");
			}

			if (storage == null) M_log.warn("no storage set: " + this.storageKey);

			storage.init();

			M_log.info("init(): storage: " + this.storage + " preLoadCache: " + this.preLoadCache);
		}
		catch (Throwable t)
		{
			M_log.warn("init(): ", t);
		}
	}

	/**
	 * {@inheritDoc}
	 */
	public Assessment newAssessment(String context) throws AssessmentPermissionException
	{
		if (context == null) throw new IllegalArgumentException();

		if (M_log.isDebugEnabled()) M_log.debug("newAssessment: " + context);

		// security check
		securityService.secure(sessionManager.getCurrentSessionUserId(), MnemeService.MANAGE_PERMISSION, context);

		AssessmentImpl rv = this.storage.newAssessment();
		rv.setContext(context);

		// if we have a gradebook, enable gb integration
		if (this.gradesService.available(context))
		{
			rv.getGrading().setGradebookIntegration(Boolean.TRUE);
		}

		save(rv);

		return rv;
	}

	/**
	 * {@inheritDoc}
	 */
	public Assessment newEmptyAssessment(String context)
	{
		if (M_log.isDebugEnabled()) M_log.debug("newEmptyAssessment: ");
		AssessmentImpl rv = this.storage.newAssessment();
		rv.setContext(context);

		return rv;
	}

	public Settings newEmptySettings()
	{
		if (M_log.isDebugEnabled()) M_log.debug("newEmptySettings: ");
		SettingsImpl rv = new SettingsImpl();

		return (Settings) rv;
	}

	/**
	 * {@inheritDoc}
	 */
	public void removeAssessment(Assessment assessment) throws AssessmentPermissionException, AssessmentPolicyException
	{
		if (assessment == null) throw new IllegalArgumentException();

		if (M_log.isDebugEnabled()) M_log.debug("removeAssessment: " + assessment.getId());

		// security check
		securityService.secure(sessionManager.getCurrentSessionUserId(), MnemeService.MANAGE_PERMISSION, assessment.getContext());

		// policy check
		if (!satisfyAssessmentRemovalPolicy(assessment)) throw new AssessmentPolicyException();

		// clear any test-drive submissions for this assessment
		this.submissionService.removeTestDriveSubmissions(assessment);

		// clear the cache
		this.threadLocalManager.set(cacheKey(assessment.getId()), null);

		// retract the test from the gb
		if (assessment.getIsValid() && assessment.getGrading().getGradebookIntegration() && assessment.getPublished())
		{
			this.gradesService.retractAssessmentGrades(assessment);
		}

		this.storage.removeAssessment((AssessmentImpl) assessment);

		// event
		eventTrackingService.post(eventTrackingService.newEvent(MnemeService.ASSESSMENT_DELETE, getAssessmentReference(assessment.getId()), true));
	}

	/**
	 * {@inheritDoc}
	 */
	public void rescoreAssessment(Assessment assessment) throws AssessmentPermissionException, GradesRejectsAssessmentException
	{
		// secure
		this.securityService.secure(this.sessionManager.getCurrentSessionUserId(), MnemeService.MANAGE_PERMISSION, assessment.getContext());

		// ignore if not locked
		if (!assessment.getIsLocked()) return;

		// pull the assessment from the grading authority
		if (assessment.getGradebookIntegration() && assessment.getPublished())
		{
			this.gradesService.retractAssessmentGrades(assessment);
		}

		// re-score
		this.submissionService.rescoreSubmission(assessment);

		// return to the grading authority
		if (assessment.getIsValid() && assessment.getGradebookIntegration() && assessment.getPublished())
		{
			// we should not be in the gb!
			if (this.gradesService.assessmentReported(assessment))
			{
				throw new GradesRejectsAssessmentException();
			}

			// try to get into the gb
			this.gradesService.initAssessmentGrades(assessment);

			// report any completed official submissions
			this.gradesService.reportAssessmentGrades(assessment);
		}
	}

	/**
	 * {@inheritDoc}
	 */
	public void saveAssessment(Assessment assessment) throws AssessmentPermissionException, AssessmentPolicyException
	{
		if (assessment == null) throw new IllegalArgumentException();
		if (assessment.getId() == null) throw new IllegalArgumentException();

		// get the current (before changes) assessment
		Assessment current = getAssessment(assessment.getId());

		// check for empty special access
		((AssessmentSpecialAccessImpl) assessment.getSpecialAccess()).consolidate();

		// if the type is changed ...
		if (((AssessmentImpl) assessment).getTypeChanged())
		{
			// ... to assignment, enforce related settings changes
			if (assessment.getType() == AssessmentType.assignment)
			{
				// assignments always are flexible
				assessment.setRandomAccess(Boolean.TRUE);

				// also default to "review available upon submission" and "manual release"
				assessment.getReview().setTiming(ReviewTiming.submitted);
				assessment.getGrading().setAutoRelease(Boolean.FALSE);
			}

			// ... to offline, adjust
			else if (assessment.getType() == AssessmentType.offline)
			{
				// remove parts
				assessment.getParts().clear();
			}

			// if changing from survey
			if (((AssessmentImpl) assessment).getOrigType() == AssessmentType.survey)
			{
				// formal course evaluation is only for surveys
				assessment.setFormalCourseEval(Boolean.FALSE);
				assessment.setNotifyEval(Boolean.FALSE);
			}
		}

		// enforce 1 try for formal course evaluation
		if (assessment.getFormalCourseEval())
		{
			assessment.setTries(1);
		}

		// for offline, enforce...
		if (assessment.getType() == AssessmentType.offline)
		{
			// review timing change from submitted to graded
			if (assessment.getReview().getTiming() == ReviewTiming.submitted) assessment.getReview().setTiming(ReviewTiming.graded);

			// 1 try, no time limit, no anon grading
			assessment.setTries(1);
			assessment.setTimeLimit(null);
			assessment.getGrading().setAnonymous(Boolean.FALSE);
		}

		// if any changes made, clear mint
		if (assessment.getIsChanged())
		{
			((AssessmentImpl) assessment).clearMint();
		}

		// otherwise we don't save: but if mint, we delete
		else
		{
			// if mint, delete instead of save
			if (((AssessmentImpl) assessment).getMint())
			{
				if (M_log.isDebugEnabled()) M_log.debug("saveAssessment: deleting mint: " + assessment.getId());

				// clear the cache
				this.threadLocalManager.set(cacheKey(assessment.getId()), null);

				this.storage.removeAssessment((AssessmentImpl) assessment);

				// event
				eventTrackingService.post(eventTrackingService.newEvent(MnemeService.ASSESSMENT_DELETE, getAssessmentReference(assessment.getId()),
						true));
			}

			return;
		}

		if (M_log.isDebugEnabled()) M_log.debug("saveAssessment: " + assessment.getId());

		// security check
		securityService.secure(sessionManager.getCurrentSessionUserId(), MnemeService.MANAGE_PERMISSION, assessment.getContext());

		// check for changes not allowed if locked
		if ((assessment.getIsLocked()) && ((AssessmentImpl) assessment).getIsLockedChanged()) throw new AssessmentPolicyException();

		// clear any test-drive submissions for this assessment
		this.submissionService.removeTestDriveSubmissions(assessment);

		// see if we need to retract or release grades
		boolean retract = false;
		boolean release = false;
		if (((AssessmentGradingImpl) (assessment.getGrading())).getAutoReleaseChanged())
		{
			if (assessment.getGrading().getAutoRelease())
			{
				release = true;
			}
			else
			{
				retract = true;
			}
		}
		// clear the auto-release change tracking
		((AssessmentGradingImpl) (assessment.getGrading())).initAutoRelease(assessment.getGrading().getAutoRelease());

		// see if we have had a title change (and clear)
		boolean titleChanged = ((AssessmentImpl) assessment).getTitleChanged();
		((AssessmentImpl) assessment).initTitle(assessment.getTitle());

		// see if we had a change in published (and clear)
		boolean publishedChanged = ((AssessmentImpl) assessment).getPublishedChanged();
		((AssessmentImpl) assessment).initPublished(assessment.getPublished());

		// see if we have had a due date change (and clear)
		boolean dueChanged = ((AssessmentDatesImpl) assessment.getDates()).getDueDateChanged();
		((AssessmentDatesImpl) assessment.getDates()).initDueDate(assessment.getDates().getDueDate());

		// see if we have just been archived (and clear)
		boolean archivedChanged = ((AssessmentImpl) assessment).getArchivedChanged();
		((AssessmentImpl) assessment).initArchived(assessment.getArchived());

		// see if we have changed our gradebook integration (and clear)
		boolean gbIntegrationChanged = ((AssessmentGradingImpl) (assessment.getGrading())).getGradebookIntegrationChanged();
		((AssessmentGradingImpl) (assessment.getGrading())).initGradebookIntegration(assessment.getGrading().getGradebookIntegration());

		// see if we need to re-score (and clear)
		boolean rescore = assessment.getIsLocked() && ((AssessmentImpl) assessment).getNeedsRescore();
		((AssessmentImpl) assessment).initNeedsRescore(false);

		// see if the type changed (and clear)
		boolean typeChanged = ((AssessmentImpl) assessment).getTypeChanged();
		assessment.initType(assessment.getType());

		// see if the points changed (and clear)
		boolean pointsChanged = ((AssessmentImpl) assessment).getPointsChanged();
		((AssessmentImpl) assessment).initPoints(assessment.getPoints());

		// make sure we are not still considered invalid for gb - if we are, we will pick that up down below
		((AssessmentGradingImpl) (assessment.getGrading())).initGradebookRejectedAssessment(Boolean.FALSE);

		// see if we have changed our validity
		boolean validityChanged = false;
		boolean nowValid = assessment.getIsValid();
		if (current != null)
		{
			validityChanged = (current.getIsValid().booleanValue() != nowValid);
		}

		// if we are just going published and not yet live, bring the assessment live
		if (!assessment.getIsLive() && publishedChanged && assessment.getPublished())
		{
			((AssessmentImpl) assessment).lock();
		}
		
		boolean isEtudesGradebookInSite = false;		
		boolean isCurrTitleAvailable = false;
		boolean isCurrTitleDefined = false;
		
		/*isEtudesGradebookInSite = etudesGradebookService.isToolAvailable(current.getContext());
		
		// etudes gradebook - duplicate titles - current assessment
		if (isEtudesGradebookInSite)
		{
			if (current.getGradebookIntegration())
			{
				isCurrTitleAvailable = etudesGradebookService.isTitleAvailable(current.getContext(), userDirectoryService.getCurrentUser().getId(), current.getTitle());
				
				GradebookItemType gradebookItemType = null;
				if (current.getType() == AssessmentType.assignment)
				{
					gradebookItemType = GradebookItemType.assignment;
				}
				else if (current.getType() == AssessmentType.test)
				{
					gradebookItemType = GradebookItemType.test;
				}
				else if (assessment.getType() == AssessmentType.offline)
				{
					gradebookItemType = GradebookItemType.offline;
				}
				else if (assessment.getType() == AssessmentType.survey)
				{
					gradebookItemType = GradebookItemType.survey;
				}
				
				isCurrTitleDefined = etudesGradebookService.isTitleDefined(current.getContext(), userDirectoryService.getCurrentUser().getId(), current.getTitle(), current.getId(), gradebookItemType);
				
				M_log.debug("isCurrTitleAvaialble :"+ isCurrTitleAvailable);
				M_log.debug("isCurrTitleDefined :"+ isCurrTitleAvailable);
			}
		}*/
		
		// check duplicate titles in gradebook - current assessment not added to gradebook  and updated assessment added to gradebook before saving the assessment
		boolean checkDuplicateTitlesNeeded = false;
		/*if (isEtudesGradebookInSite && !isCurrTitleDefined && assessment.getGradebookIntegration())
		{
			boolean isTitleAvailable = etudesGradebookService.isTitleAvailable(assessment.getContext(), userDirectoryService.getCurrentUser().getId(), assessment.getTitle());
			
			M_log.debug("isTitleAvaialble :"+ isTitleAvailable);
			
			if (!isTitleAvailable)
			{
				// mark as invalid
				((AssessmentGradingImpl) (assessment.getGrading())).initGradebookRejectedAssessment(Boolean.TRUE);

				// save
				//save((AssessmentImpl) assessment);
				
				checkDuplicateTitlesNeeded = true;
			}
		}*/

		// save the changes
		save((AssessmentImpl) assessment);

		// event for change in published
		if (publishedChanged)
		{
			if (assessment.getPublished())
			{
				eventTrackingService.post(eventTrackingService.newEvent(MnemeService.ASSESSMENT_PUBLISH, getAssessmentReference(assessment.getId()),
						true));
			}
			else
			{
				eventTrackingService.post(eventTrackingService.newEvent(MnemeService.ASSESSMENT_UNPUBLISH,
						getAssessmentReference(assessment.getId()), true));
			}
		}
		
		// etudes gradebook - duplicate titles
		/*if (isEtudesGradebookInSite && checkDuplicateTitlesNeeded)
		{
			if (isCurrTitleDefined)
			{
				// check for changes - type, title etc
				if (assessment.getGradebookIntegration())
				{
					GradebookItemType gradebookItemType = null;
					if (assessment.getType() == AssessmentType.assignment)
					{
						gradebookItemType = GradebookItemType.assignment;
					}
					else if (assessment.getType() == AssessmentType.test)
					{
						gradebookItemType = GradebookItemType.test;
					}
					else if (assessment.getType() == AssessmentType.offline)
					{
						gradebookItemType = GradebookItemType.offline;
					}
					else if (assessment.getType() == AssessmentType.survey)
					{
						gradebookItemType = GradebookItemType.survey;
					}
					
					boolean isTitleDefined = etudesGradebookService.isTitleDefined(assessment.getContext(), userDirectoryService.getCurrentUser().getId(), assessment.getTitle(), assessment.getId(), gradebookItemType);
					
					M_log.debug("isTitleDefined :"+ isTitleDefined);
					
					if (!isTitleDefined)
					{
						// title might have changed
						boolean isTitleAvailable = etudesGradebookService.isTitleAvailable(assessment.getContext(), userDirectoryService.getCurrentUser().getId(), assessment.getTitle());
						
						M_log.debug("isTitleAvaialble :"+ isTitleAvailable);
						
						if (!isTitleAvailable)
						{
							// mark as invalid
							((AssessmentGradingImpl) (assessment.getGrading())).initGradebookRejectedAssessment(Boolean.TRUE);
	
							// re-save
							save((AssessmentImpl) assessment);
						}
					}
					
				}
			}
			else
			{
				// check for title availability
				if (assessment.getGradebookIntegration())
				{
					boolean isTitleAvailable = etudesGradebookService.isTitleAvailable(assessment.getContext(), userDirectoryService.getCurrentUser().getId(), assessment.getTitle());
					
					M_log.debug("isTitleAvaialble :"+ isTitleAvailable);
					
					if (!isTitleAvailable)
					{
						// mark as invalid
						((AssessmentGradingImpl) (assessment.getGrading())).initGradebookRejectedAssessment(Boolean.TRUE);
						
						if (assessment.getPublished())
						{
							assessment.setPublished(Boolean.FALSE);
						}
	
						// re-save
						save((AssessmentImpl) assessment);
					}
				}
			}
		}*/

		// if the name or due date has changed, or we are retracting submissions, or we are now unpublished,
		// or we are now invalid, or we have just been archived, or we are now not gradebook integrated,
		// or we are releasing (we need to remove our entry so we can add it back without conflict)
		// or we changed type to survey or our points have changed
		// retract the assessment from the grades authority
		if (rescore || titleChanged || dueChanged || retract || release || (publishedChanged && !assessment.getPublished())
				|| (validityChanged && !nowValid) || (archivedChanged && assessment.getArchived()) || pointsChanged
				|| (gbIntegrationChanged && !assessment.getGradebookIntegration()) || (typeChanged && assessment.getType() == AssessmentType.survey))
		{
			// retract the entire assessment from grades - use the old information (title) (if we existed before this call)
			// ONLY IF we were expecting to be in the gb based on current values
			if ((current != null) && current.getIsValid() && current.getGradebookIntegration() && current.getPublished())
			{
				this.gradesService.retractAssessmentGrades(current);
			}

			// retract the submissions
			if (retract)
			{
				this.submissionService.retractSubmissions(assessment);
			}
		}

		// re-score the submissions if needed
		if (rescore)
		{
			this.submissionService.rescoreSubmission(assessment);
		}

		// if the name or due date has changed, or we are releasing submissions, or we are now published,
		// or we are now valid (and are published), or we are now gradebook integrated,
		// or we are retracting (we need to add the entry back in that we just removed)
		// report the assessment and all completed submissions to the grades authority
		if (rescore || titleChanged || dueChanged || release || retract || (publishedChanged && assessment.getPublished()) || pointsChanged
				|| (validityChanged && nowValid && assessment.getPublished()) || (gbIntegrationChanged && assessment.getGradebookIntegration()))
		{
			if (assessment.getIsValid() && assessment.getGradebookIntegration() && assessment.getPublished())
			{
				try
				{
					// we should not be in the gb!
					if (this.gradesService.assessmentReported(assessment))
					{
						throw new GradesRejectsAssessmentException();
					}

					// try to get into the gb
					this.gradesService.initAssessmentGrades(assessment);

					// report any completed official submissions
					this.gradesService.reportAssessmentGrades(assessment);
				}
				catch (GradesRejectsAssessmentException e)
				{
					// mark as invalid
					((AssessmentGradingImpl) (assessment.getGrading())).initGradebookRejectedAssessment(Boolean.TRUE);

					// re-save
					save((AssessmentImpl) assessment);
				}
			}

			// release the submissions, if we need to (each will have the grade reported)
			if (release)
			{
				this.submissionService.releaseSubmissions(assessment, Boolean.FALSE);
			}
		}

		// our change might make other gradebook-invalid assessments valid - only if we were in the gb to stat with
		if ((current != null) && current.getPublished() && current.getGradebookIntegration() && (!current.getArchived()) && current.getIsValid())
		{
			if (titleChanged || (publishedChanged && (!assessment.getPublished())) || (archivedChanged && assessment.getArchived())
					|| (gbIntegrationChanged && (!assessment.getGradebookIntegration())))
			{

				// get all context assessments that are invalid due to gb integration
				List<AssessmentImpl> gbInvalid = this.storage.getContextGbInvalidAssessments(assessment.getContext());

				// for each one
				for (AssessmentImpl a : gbInvalid)
				{
					// clear the invalid (so it does not trigger the getIsValid call)
					((AssessmentGradingImpl) (a.getGrading())).initGradebookRejectedAssessment(Boolean.FALSE);

					if (a.getIsValid() && a.getGradebookIntegration() && a.getPublished())
					{
						try
						{
							// we should not be in the gb!
							if (this.gradesService.assessmentReported(a))
							{
								throw new GradesRejectsAssessmentException();
							}

							// try to get into the gb
							this.gradesService.initAssessmentGrades(a);

							// report any completed official submissions
							this.gradesService.reportAssessmentGrades(a);

							// save (the invalid flag is cleared)
							save((AssessmentImpl) a);
						}
						catch (GradesRejectsAssessmentException e)
						{
						}
					}
				}
			}
		}
	}

	/**
	 * {@inheritDoc}
	 */
	public void sendEvalNotification(Assessment assessment)
	{
		if (assessment == null) throw new IllegalArgumentException();

		// do it - the submission service handles this
		((SubmissionServiceImpl) this.submissionService).notifyStudentEvaluation(assessment);
	}

	/**
	 * {@inheritDoc}
	 */
	public void sendResults(Assessment assessment)
	{
		if (assessment == null) throw new IllegalArgumentException();

		// must be set for email, and be closed
		if (assessment.getResultsEmail() == null) return;
		if (!assessment.getDates().getIsClosed()) return;

		// do it - the submission service handles this
		((SubmissionServiceImpl) this.submissionService).emailResults(assessment);
	}

	/**
	 * Dependency: AttachmentService.
	 * 
	 * @param service
	 *        The AttachmentService.
	 */
	public void setAttachmentService(AttachmentService service)
	{
		attachmentService = service;
	}

	/**
	 * {@inheritDoc}
	 */
	public void setEvaluationSent(Assessment assessment, Date date)
	{
		if (assessment == null) throw new IllegalArgumentException();
		if (date == null) throw new IllegalArgumentException();

		// TODO: security?
		this.storage.setEvaluationSent(assessment.getId(), date);
	}

	/**
	 * Dependency: EventTrackingService.
	 * 
	 * @param service
	 *        The EventTrackingService.
	 */
	public void setEventTrackingService(EventTrackingService service)
	{
		eventTrackingService = service;
	}

	public void setExportService(ExportQtiServiceImpl exportService)
	{
		this.exportService = exportService;
	}

	/**
	 * Dependency: GradesService.
	 * 
	 * @param service
	 *        The GradesService.
	 */
	public void setGradesService(GradesService service)
	{
		this.gradesService = service;
	}

	/**
	 * Dependency: PoolService.
	 * 
	 * @param service
	 *        The PoolService.
	 */
	public void setPoolService(PoolService service)
	{
		poolService = service;
	}

	/**
	 * Set the preLoadCache setting.
	 * 
	 * @param value
	 *        The preLoadCache setting.
	 */
	public void setPreLoadCache(boolean value)
	{
		this.preLoadCache = value;
	}

	/**
	 * Dependency: QuestionService.
	 * 
	 * @param service
	 *        The QuestionService.
	 */
	public void setQuestionService(QuestionService service)
	{
		questionService = service;
	}

	/**
	 * {@inheritDoc}
	 */
	public void setResultsSent(Assessment assessment, Date date)
	{
		if (assessment == null) throw new IllegalArgumentException();
		if (date == null) throw new IllegalArgumentException();

		// TODO: security?
		this.storage.setResultsSent(assessment.getId(), date);
	}

	/**
	 * Dependency: SecurityService.
	 * 
	 * @param service
	 *        The SecurityService.
	 */
	public void setSecurityService(SecurityService service)
	{
		securityService = service;
	}

	/**
	 * Dependency: SessionManager.
	 * 
	 * @param service
	 *        The SessionManager.
	 */
	public void setSessionManager(SessionManager service)
	{
		sessionManager = service;
	}

	/**
	 * Dependency: SqlService.
	 * 
	 * @param service
	 *        The SqlService.
	 */
	public void setSqlService(SqlService service)
	{
		sqlService = service;
	}

	/**
	 * Set the storage class options.
	 * 
	 * @param options
	 *        The PoolStorage options.
	 */
	@SuppressWarnings(
	{ "unchecked", "rawtypes" })
	public void setStorage(Map options)
	{
		this.storgeOptions = options;
	}

	/**
	 * Set the storage option key to use, selecting which PoolStorage to use.
	 * 
	 * @param key
	 *        The storage option key.
	 */
	public void setStorageKey(String key)
	{
		this.storageKey = key;
	}

	/**
	 * Dependency: SubmissionService.
	 * 
	 * @param service
	 *        The SubmissionService.
	 */
	public void setSubmissionService(SubmissionService service)
	{
		submissionService = (SubmissionServiceImpl) service;
	}

	/**
	 * Dependency: ThreadLocalManager.
	 * 
	 * @param service
	 *        The SqlService.
	 */
	public void setThreadLocalManager(ThreadLocalManager service)
	{
		threadLocalManager = service;
	}

	/**
	 * Dependency: UserDirectoryService.
	 * 
	 * @param service
	 *        The UserDirectoryService.
	 */
	public void setUserDirectoryService(UserDirectoryService service)
	{
		userDirectoryService = service;
	}

	/**
	 * Form a key for caching an assessment.
	 * 
	 * @param assessmentId
	 *        The assessment id.
	 * @return The cache key.
	 */
	protected String cacheKey(String assessmentId)
	{
		String key = "mneme:assessment:" + assessmentId;
		return key;
	}

	/**
	 * Copy an assessment
	 * 
	 * @param context
	 *        The destination context.
	 * @param assessment
	 *        The source assessment.
	 * @param pidMap
	 *        A map (old pool id -> new pool id) to use to convert all pool references.
	 * @param qidMap
	 *        A map (old question id -> new question id) to use to convert all question references.
	 * @param appendTitle
	 *        if true, append text to the title, else leave the title an exact copy.
	 * @param attachmentTranslations
	 *        A list of Translations for attachments and embedded media.
	 */
	protected AssessmentImpl doCopyAssessment(String context, Assessment assessment, Map<String, String> pidMap, Map<String, String> qidMap,
			boolean appendTitle, List<Translation> attachmentTranslations)
	{
		if (context == null) throw new IllegalArgumentException();
		if (assessment == null) throw new IllegalArgumentException();

		if (M_log.isDebugEnabled()) M_log.debug("doCopyAssessment: context:" + context + " id: " + assessment.getId());

		String userId = sessionManager.getCurrentSessionUserId();
		Date now = new Date();

		AssessmentImpl rv = this.storage.clone((AssessmentImpl) assessment);

		// clear the id to make it a new one
		rv.id = null;

		// set the context
		rv.setContext(context);

		// add to the title
		if (appendTitle)
		{
			rv.setTitle(((PoolServiceImpl) this.poolService).addDate("copy-text", rv.getTitle(), now));
		}

		// clear archived
		rv.initArchived(Boolean.FALSE);

		// clear out any special access
		rv.getSpecialAccess().clear();

		// start out unpublished
		rv.initPublished(Boolean.FALSE);

		// clear frozen
		rv.initFrozen(Boolean.FALSE);

		// and not-live, non-locked
		rv.initLive(Boolean.FALSE);
		rv.initLocked(Boolean.FALSE);

		// email results not sent
		rv.initResultsSent(null);

		// open notification not sent
		rv.initEvalSent(null);

		((AssessmentGradingImpl) (rv.getGrading())).initGradebookRejectedAssessment(Boolean.FALSE);

		// update created and last modified information
		rv.getCreatedBy().setDate(now);
		rv.getCreatedBy().setUserId(userId);
		rv.getModifiedBy().setDate(now);
		rv.getModifiedBy().setUserId(userId);

		// set the parts to their original question and pool values
		for (Part part : rv.getParts().getParts())
		{
			// if any detail fails to restore, remove it
			for (Iterator<PartDetail> i = part.getDetails().iterator(); i.hasNext();)
			{
				PartDetail detail = i.next();
				if (!detail.restoreToOriginal(pidMap, qidMap))
				{
					i.remove();
				}
			}
		}

		// translate embedded media references
		if (attachmentTranslations != null)
		{
			// question presentation text and attachments
			rv.getPresentation().setText(this.attachmentService.translateEmbeddedReferences(rv.getPresentation().getText(), attachmentTranslations));
			List<Reference> attachments = rv.getPresentation().getAttachments();
			List<Reference> newAttachments = new ArrayList<Reference>();
			for (Reference ref : attachments)
			{
				String newRef = ref.getReference();
				for (Translation t : attachmentTranslations)
				{
					newRef = t.translate(newRef);
				}
				newAttachments.add(this.attachmentService.getReference(newRef));
			}
			rv.getPresentation().setAttachments(newAttachments);

			// submit message text and attachments
			rv.getSubmitPresentation().setText(
					this.attachmentService.translateEmbeddedReferences(rv.getSubmitPresentation().getText(), attachmentTranslations));
			attachments = rv.getSubmitPresentation().getAttachments();
			newAttachments = new ArrayList<Reference>();
			for (Reference ref : attachments)
			{
				String newRef = ref.getReference();
				for (Translation t : attachmentTranslations)
				{
					newRef = t.translate(newRef);
				}
				newAttachments.add(this.attachmentService.getReference(newRef));
			}
			rv.getSubmitPresentation().setAttachments(newAttachments);

			for (Part p : rv.getParts().getParts())
			{
				// part instruction and attachments
				p.getPresentation()
						.setText(this.attachmentService.translateEmbeddedReferences(p.getPresentation().getText(), attachmentTranslations));
				attachments = p.getPresentation().getAttachments();
				newAttachments = new ArrayList<Reference>();
				for (Reference ref : attachments)
				{
					String newRef = ref.getReference();
					for (Translation t : attachmentTranslations)
					{
						newRef = t.translate(newRef);
					}
					newAttachments.add(this.attachmentService.getReference(newRef));
				}
				p.getPresentation().setAttachments(newAttachments);
			}
		}

		// change the auto-pool to the imported version of the pool
		if (rv.poolId != null)
		{
			// if we have pool translations, see if we can find our auto-pool in the new set (would happen on an import assessment from site)
			if (pidMap != null)
			{
				String translated = pidMap.get(rv.poolId);
				if (translated != null)
				{
					rv.poolId = translated;
				}
				else
				{
					rv.poolId = null;
				}
			}

			// otherwise just clear our auto-pool (would happen on a copy assessment)
			else
			{
				rv.poolId = null;
			}
		}

		// save
		this.storage.saveAssessment(rv);

		// event
		eventTrackingService.post(eventTrackingService.newEvent(MnemeService.ASSESSMENT_NEW, getAssessmentReference(rv.getId()), true));

		return rv;
	}

	/**
	 * Form an assessment reference for this assessment id.
	 * 
	 * @param assessmentId
	 *        the assessment id.
	 * @return the assessment reference for this assessment id.
	 */
	protected String getAssessmentReference(String assessmentId)
	{
		String ref = MnemeService.REFERENCE_ROOT + "/" + MnemeService.ASSESSMENT_TYPE + "/" + assessmentId;
		return ref;
	}

	/**
	 * Set this assessment to be live.
	 * 
	 * @param assessment
	 *        The assessment.
	 */
	protected void makeLive(Assessment assessment)
	{
		// clear the cache
		this.threadLocalManager.set(cacheKey(assessment.getId()), null);

		this.storage.makeLive(assessment);
	}

	/**
	 * Remove any draw dependencies on this pool from all unlocked assessments.
	 * 
	 * @param question
	 *        The question.
	 */
	protected void removeDependency(Pool pool)
	{
		// clear any test-drive submissions for this assessment
		this.submissionService.removeTestDriveSubmissions(pool.getContext());

		this.storage.removeDependency(pool);
	}

	/**
	 * Remove any pick dependencies on this question from all unlocked assessments.
	 * 
	 * @param question
	 *        The question.
	 */
	protected void removeDependency(Question question)
	{
		// clear any test-drive submissions for this assessment
		this.submissionService.removeTestDriveSubmissions(question.getContext());

		this.storage.removeDependency(question);
	}

	/**
	 * Check if this assessment meets the delete policy.
	 * 
	 * @param assessment
	 *        The assessment.
	 * @return TRUE if the assessment may be deleted, FALSE if not.
	 */
	protected Boolean satisfyAssessmentRemovalPolicy(Assessment assessment)
	{
		// live tests may not be deleted
		if (assessment.getIsLive()) return Boolean.FALSE;

		return Boolean.TRUE;
	}
	
	/**
	 * Save the assessment
	 * 
	 * @param assessment
	 *        The assessment.
	 */
	protected void save(AssessmentImpl assessment)
	{
		if (M_log.isDebugEnabled()) M_log.debug("save: " + assessment.getId());

		Date now = new Date();
		String userId = sessionManager.getCurrentSessionUserId();

		String event = MnemeService.ASSESSMENT_EDIT;

		// if the assessment is new (i.e. no id), set the createdBy information, if not already set
		if (assessment.getId() == null)
		{
			if (assessment.getCreatedBy().getUserId() == null)
			{
				assessment.getCreatedBy().setDate(now);
				assessment.getCreatedBy().setUserId(userId);
			}

			event = MnemeService.ASSESSMENT_NEW;
		}

		// update last modified information
		assessment.getModifiedBy().setDate(now);
		assessment.getModifiedBy().setUserId(userId);

		// save
		this.storage.saveAssessment(assessment);

		// clear the cache
		this.threadLocalManager.set(cacheKey(assessment.getId()), null);

		// event
		eventTrackingService.post(eventTrackingService.newEvent(event, getAssessmentReference(assessment.getId()), true));
	}
}