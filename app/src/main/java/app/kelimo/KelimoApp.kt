package app.kelimo

import android.app.Application
import app.kelimo.ai.LlmEngine
import app.kelimo.ai.LearnerCtx
import app.kelimo.ai.ModelFiles
import app.kelimo.ai.Tutor
import app.kelimo.core.Lang
import app.kelimo.course.Course
import app.kelimo.course.CourseRepository
import app.kelimo.data.Store
import app.kelimo.learn.Reminders
import app.kelimo.speech.Stt
import app.kelimo.speech.Tts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class KelimoApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var store: Store; private set
    lateinit var courses: CourseRepository; private set
    lateinit var modelFiles: ModelFiles; private set
    lateinit var llm: LlmEngine; private set
    lateinit var tutor: Tutor; private set
    val tts by lazy { Tts(this) }
    val stt by lazy { Stt(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        store = Store(this, scope)
        courses = CourseRepository(this)
        modelFiles = ModelFiles(this)
        llm = LlmEngine(this, modelFiles)
        tutor = Tutor(llm)
        Reminders.createChannel(this)
        if (store.value.activeModel == null) llm.markNoModel() else llm.markIdle()
    }

    /** Loads the active model in the background if it isn't loaded yet. */
    fun warmUpModel() {
        val s = store.value
        val m = s.activeModel ?: return
        if (llm.isReady) return
        scope.launch { llm.load(m, s.settings.backend) }
    }

    /** Loads the active model if needed and waits for it. False when no model is installed or loading failed. */
    suspend fun ensureModel(): Boolean {
        if (llm.isReady) return true
        val s = store.value
        val m = s.activeModel ?: return false
        llm.load(m, s.settings.backend)
        return llm.isReady
    }

    /** Curated course, or AI lessons wrapped as a course for other languages. */
    fun currentCourse(): Course {
        val s = store.value
        return courses.course(s.profile.target) ?: courses.aiCourse(s.profile.target, s.profile.native, s.course.aiLessons)
    }

    fun learner(): LearnerCtx {
        val s = store.value
        val course = courses.course(s.profile.target)
        val known = course?.allWords?.filter { s.course.srs["w:${it.key}"]?.reps?.let { r -> r > 0 } == true }?.map { it.t }.orEmpty()
        return LearnerCtx(Lang.of(s.profile.native), Lang.of(s.profile.target), s.course.cefr, s.profile.name, s.profile.interests, known)
    }

    companion object {
        lateinit var instance: KelimoApp; private set
    }
}
