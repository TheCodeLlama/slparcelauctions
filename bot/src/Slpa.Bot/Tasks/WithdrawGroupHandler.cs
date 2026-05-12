using Microsoft.Extensions.Logging;
using Slpa.Bot.Backend;
using Slpa.Bot.Backend.Models;
using Slpa.Bot.Sl;

namespace Slpa.Bot.Tasks;

/// <summary>
/// Sub-project G §7.4 -- handles WITHDRAW_GROUP tasks by issuing a
/// <c>Self.GiveGroupMoney</c> transfer from the logged-in avatar to the
/// registered SL group. The backend has already debited the realty-group
/// wallet and written the WITHDRAW_QUEUED ledger row; the bot's role is
/// the in-world transfer itself. Success vs. failure surfaces via the
/// money-tracker callback path, not via this handler's return value.
/// </summary>
public sealed class WithdrawGroupHandler
{
    private readonly IBotSession _session;
    private readonly IBackendClient _backend;
    private readonly ILogger<WithdrawGroupHandler> _log;

    public WithdrawGroupHandler(
        IBotSession session,
        IBackendClient backend,
        ILogger<WithdrawGroupHandler> log)
    {
        _session = session;
        _backend = backend;
        _log = log;
    }

    public Task HandleAsync(BotTaskResponse task, CancellationToken ct)
    {
        if (task.RecipientUuid is null || task.AmountL is null)
        {
            _log.LogWarning(
                "WITHDRAW_GROUP {TaskId} missing RecipientUuid or AmountL; skipping",
                task.Id);
            return Task.CompletedTask;
        }

        var memo = $"SLPA group wallet withdraw ref {task.Id}";
        var amountL = (int)task.AmountL.Value;
        _session.GiveGroupMoney(task.RecipientUuid.Value, amountL, memo);
        _log.LogInformation(
            "WITHDRAW_GROUP {TaskId} issued GiveGroupMoney to {GroupUuid}, L${Amount}",
            task.Id, task.RecipientUuid.Value, amountL);
        return Task.CompletedTask;
    }
}
